package net.smithed.summitsync;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.FunctionInstantiationException;
import net.minecraft.commands.execution.ExecutionContext;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.commands.functions.InstantiatedFunction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FunctionExecutor {
    public static void invokeFunctionEvent(ServerPlayer player, CompoundTag args, Identifier tag) {
        var server = player.level().getServer();
        var functions = server.getFunctions();
        var dispatcher = server.getCommands().getDispatcher();

        var joinFunctions = functions.getTag(tag);
        for (var func : joinFunctions) {
            execute(func, server.createCommandSourceStack().withEntity(player), dispatcher, args);
        }
    }

    public static void execute(final CommandFunction<CommandSourceStack> functionIn, final CommandSourceStack sender, final CommandDispatcher<CommandSourceStack> dispatcher, final CompoundTag arguments) {
        ProfilerFiller profiler = Profiler.get();
        profiler.push(() -> "event-function " + functionIn.id());

        try {
            InstantiatedFunction<CommandSourceStack> function = functionIn.instantiate(arguments, dispatcher);
            Commands.executeCommandInContext(sender, context -> ExecutionContext.queueInitialFunctionCall(context, function, sender, CommandResultCallback.EMPTY));
        } catch (FunctionInstantiationException e) {
            SummitSync.LOGGER.error("Failed to instantiate player event function {}\n{}", functionIn.id(), e);
        } catch (Exception e) {
            SummitSync.LOGGER.warn("Failed to execute function {}", functionIn.id(), e);
        } finally {
            profiler.pop();
        }
    }
}
