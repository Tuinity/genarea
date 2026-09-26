package ca.spottedleaf.genarea;

import ca.spottedleaf.genarea.command.GenAreaCommand;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

public final class GenAreaMod implements ModInitializer {

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((
            final CommandDispatcher<CommandSourceStack> dispatcher,
            final CommandBuildContext registryAccess,
            final Commands.CommandSelection environment
        ) -> {
            GenAreaCommand.register(dispatcher);
        });
    }
}
