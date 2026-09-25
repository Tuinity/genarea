package ca.spottedleaf.genarea.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.coordinates.ColumnPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ColumnPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.slf4j.Logger;
import java.text.DecimalFormat;
import java.util.NoSuchElementException;
import java.util.PrimitiveIterator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class GenAreaCommand {

    public static final ThreadLocal<DecimalFormat> FOUR_DECIMAL_PLACES = ThreadLocal.withInitial(() -> {
        return new DecimalFormat("#,##0.0000");
    });
    public static final ThreadLocal<DecimalFormat> THREE_DECIMAL_PLACES = ThreadLocal.withInitial(() -> {
        return new DecimalFormat("#,##0.000");
    });
    public static final ThreadLocal<DecimalFormat> TWO_DECIMAL_PLACES = ThreadLocal.withInitial(() -> {
        return new DecimalFormat("#,##0.00");
    });
    public static final ThreadLocal<DecimalFormat> ONE_DECIMAL_PLACES = ThreadLocal.withInitial(() -> {
        return new DecimalFormat("#,##0.0");
    });
    public static final ThreadLocal<DecimalFormat> NO_DECIMAL_PLACES = ThreadLocal.withInitial(() -> {
        return new DecimalFormat("#,##0");
    });

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final TextColor LIGHT_RED = TextColor.fromRgb(0xFF8080);

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            literal("genarea").requires((final CommandSourceStack src) -> {
                return Commands.hasPermission(Commands.LEVEL_ADMINS).test(src) || !(src.getServer() instanceof DedicatedServer);
            }).then(
                argument("world", DimensionArgument.dimension())
                    .then(
                        argument("center", ColumnPosArgument.columnPos())
                            .then(
                                argument("radius", IntegerArgumentType.integer(0, Integer.MAX_VALUE))
                                    .then(
                                        argument("max_loaded", IntegerArgumentType.integer(4, Integer.MAX_VALUE))
                                            .executes((final CommandContext<CommandSourceStack> ctx) -> {
                                                return GenAreaCommand.genArea(
                                                    ctx,
                                                    DimensionArgument.getDimension(ctx, "world"),
                                                    ColumnPosArgument.getColumnPos(ctx, "center"),
                                                    IntegerArgumentType.getInteger(ctx, "radius"),
                                                    IntegerArgumentType.getInteger(ctx, "max_loaded")
                                                );
                                            })
                                    )
                            )
                    )
            )
        );
    }

    private static final class SquareIterator implements PrimitiveIterator.OfLong {

        private final int centerX;
        private final int centerZ;
        private final int radius;

        private int dx;
        private int dz;

        public SquareIterator(final int centerX, final int centerZ, final int radius) {
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.radius = radius;
            this.dx = -radius;
            this.dz = -radius;
        }


        @Override
        public long nextLong() {
            if (!this.hasNext()) {
                throw new NoSuchElementException();
            }

            final int dx = this.dx;
            final int dz = this.dz;

            if (++this.dx > this.radius) {
                this.dx = -this.radius;
                ++this.dz;
            }

            return ChunkPos.pack(dx + this.centerX, dz + this.centerZ);
        }

        @Override
        public boolean hasNext() {
            return this.dz <= this.radius;
        }
    }

    private static final class SquareDividedIterator implements PrimitiveIterator.OfLong {

        private final int centerX;
        private final int centerZ;
        private final int radius;
        private final int divisor;
        private final int totalDivs;

        private int dz;
        private int div;
        private int divStart;
        private int divV;

        public SquareDividedIterator(final int centerX, final int centerZ, final int radius, final int max) {
            if (max < 2) {
                throw new IllegalArgumentException("Max must be > 1");
            }

            this.centerX = centerX;
            this.centerZ = centerZ;
            this.radius = radius;
            this.divisor = Math.min(2*radius+1, max);
            this.totalDivs = (2*radius+1+(this.divisor - 1)) / this.divisor;

            this.dz = -radius;
            this.div = 0;
            this.divStart = 0;
            this.divV = 0;
        }

        @Override
        public long nextLong() {
            if (!this.hasNext()) {
                throw new NoSuchElementException();
            }

            final int dz = this.dz;
            final int dx = this.divV - this.radius;

            if (++this.divV >= Math.min(this.divStart+this.divisor, 2*this.radius+1)) {
                if (++this.dz > this.radius) {
                    ++this.div;
                    this.divStart += this.divisor;
                    this.dz = -this.radius;
                }
                this.divV = this.divStart;
            }


            return ChunkPos.pack(dx + this.centerX, dz + this.centerZ);
        }

        @Override
        public boolean hasNext() {
            return this.div < this.totalDivs;
        }
    }

    private static final boolean USE_DIVIDED_STRAT = true;

    private static final TicketType GEN_AREA_TICKET = new TicketType(0L, TicketType.FLAG_LOADING);

    private static final record AreaGenTask(
        ServerLevel world, PrimitiveIterator.OfLong chunkIterator,
        long startNS, int maxWorking, LongOpenHashSet generating,
        AtomicInteger generated, int total,
        AtomicLong lastLog
    ) {
        private static final long LOG_INTERVAL = TimeUnit.SECONDS.toNanos(1L);

        public void start() {
            while (this.tryFetchNext());
        }

        private void tryLog(final int generated) {
            final long now = System.nanoTime();
            final long lastLog = this.lastLog.get();
            if ((now - lastLog < LOG_INTERVAL && generated != this.total) || !this.lastLog.compareAndSet(lastLog, now)) {
                return;
            }

            final double rate = (double)generated / ((double)(now - this.startNS) / (double)TimeUnit.SECONDS.toNanos(1L));
            final double progress = 100.0 * ((double)generated / (double)(this.total));
            final double timeS = (double)(now - this.startNS) / (double)TimeUnit.SECONDS.toNanos(1L);

            this.world.getServer().sendSystemMessage(
                Component.literal("Generated ").withStyle(ChatFormatting.BLUE)
                    .append(Component.literal(NO_DECIMAL_PLACES.get().format((long)this.generated.get())).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal("/").withStyle(ChatFormatting.BLUE))
                    .append(Component.literal(NO_DECIMAL_PLACES.get().format((long)this.total)).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(", rate=").withStyle(ChatFormatting.BLUE))
                    .append(Component.literal(ONE_DECIMAL_PLACES.get().format(rate)).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal("chunks/s, progress=").withStyle(ChatFormatting.BLUE))
                    .append(Component.literal(TWO_DECIMAL_PLACES.get().format(progress)).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal("%, time=").withStyle(ChatFormatting.BLUE))
                    .append(Component.literal(ONE_DECIMAL_PLACES.get().format(timeS)).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal( "s").withStyle(ChatFormatting.BLUE))
            );
        }

        public void finish(final int x, final int z) {
            final int generated = this.generated.incrementAndGet();

            this.generating.remove(ChunkPos.pack(x, z));

            this.tryLog(generated);

            while (this.tryFetchNext());

            this.world.getChunkSource().removeTicketWithRadius(GEN_AREA_TICKET, new ChunkPos(x, z), 0);
        }

        private boolean tryFetchNext() {
            if (this.generating.size() >= this.maxWorking) {
                return false;
            }

            if (!this.chunkIterator.hasNext()) {
                return false;
            }

            final long toGen = this.chunkIterator.next();

            this.generating.add(toGen);

            final int chunkX = ChunkPos.getX(toGen);
            final int chunkZ = ChunkPos.getZ(toGen);

            this.world.getChunkSource().addTicketWithRadius(GEN_AREA_TICKET, new ChunkPos(chunkX, chunkZ), 0);

            final BiConsumer<ChunkResult<ChunkAccess>, Throwable> onComplete = (final ChunkResult<ChunkAccess> chunk, final Throwable thr) -> {
                AreaGenTask.this.finish(chunkX, chunkZ);
                AreaGenTask.this.world.getServer().emptyTicks = 0;
            };

            // avoid recursion for loaded chunks by scheduling a later chunk task
            this.world.getChunkSource().mainThreadProcessor.execute(() -> {
                AreaGenTask.this.world.getChunkSource().getChunkFutureMainThread(
                    chunkX, chunkZ, ChunkStatus.FULL, true
                ).whenCompleteAsync(
                    onComplete, (final Runnable run) -> {
                        if (this.world.getChunkSource().mainThreadProcessor.isSameThread()) {
                            run.run();
                        } else {
                            AreaGenTask.this.world.getChunkSource().mainThreadProcessor.execute(run);
                        }
                    }
                );
            });

            return true;
        }
    }

    private static int genArea(final CommandContext<CommandSourceStack> ctx, final ServerLevel world, final ColumnPos center, final int radius, final int maxLoaded) {
        final int centerX = center.x() >> 4;
        final int centerZ = center.z() >> 4;

        final long minBlockX = ((long)centerX << 4) - ((long)radius << 4);
        final long maxBlockX = ((long)centerX << 4) + ((long)radius << 4) + 15L;
        final long minBlockZ = ((long)centerZ << 4) - ((long)radius << 4);
        final long maxBlockZ = ((long)centerZ << 4) + ((long)radius << 4) + 15L;

        if (minBlockX <= -Level.MAX_LEVEL_SIZE || maxBlockX >= Level.MAX_LEVEL_SIZE
            || minBlockZ <= -Level.MAX_LEVEL_SIZE || maxBlockZ >= Level.MAX_LEVEL_SIZE) {
            ctx.getSource().sendFailure(
                Component.literal("Generated area ").withStyle(ChatFormatting.RED)
                    .append(Component.literal("[" + minBlockX + "," + minBlockZ + "]").withColor(LIGHT_RED))
                    .append(Component.literal(" -> ").withStyle(ChatFormatting.RED))
                    .append(Component.literal("[" + maxBlockX + "," + maxBlockZ + "]").withColor(LIGHT_RED))
                    .append(Component.literal(" is out of world bounds").withStyle(ChatFormatting.RED))
            );
            return 0;
        }

        ctx.getSource().sendSuccess(() -> {
            return Component.literal("Generating area ").withStyle(ChatFormatting.BLUE)
                .append(Component.literal("[" + minBlockX + "," + minBlockZ + "]").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" -> ").withStyle(ChatFormatting.BLUE))
                .append(Component.literal("[" + maxBlockX + "," + maxBlockZ + "]").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(", check logs for progress").withStyle(ChatFormatting.BLUE));
        }, true);

        final PrimitiveIterator.OfLong iterator = USE_DIVIDED_STRAT ? new SquareDividedIterator(centerX, centerZ, radius, maxLoaded) : new SquareIterator(centerX, centerZ, radius);

        final long start = System.nanoTime();
        new AreaGenTask(
            world, iterator, start, maxLoaded,
            new LongOpenHashSet(maxLoaded), new AtomicInteger(), (2*radius+1)*(2*radius+1),
            new AtomicLong(start)
        ).start();

        return Command.SINGLE_SUCCESS;
    }
}
