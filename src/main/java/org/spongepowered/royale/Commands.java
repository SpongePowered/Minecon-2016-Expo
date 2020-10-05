/*
 * This file is part of Royale, licensed under the MIT License (MIT).
 *
 * Copyright (c) SpongePowered <http://github.com/SpongePowered>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.spongepowered.royale;

import com.google.common.collect.Iterables;
import com.google.common.reflect.TypeToken;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.LinearComponents;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import ninja.leaping.configurate.objectmapping.ObjectMappingException;
import org.spongepowered.api.ResourceKey;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.adventure.SpongeComponents;
import org.spongepowered.api.command.Command;
import org.spongepowered.api.command.CommandCause;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.exception.CommandException;
import org.spongepowered.api.command.parameter.CommandContext;
import org.spongepowered.api.command.parameter.CommonParameters;
import org.spongepowered.api.command.parameter.Parameter;
import org.spongepowered.api.command.parameter.managed.standard.CatalogedValueParameters;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.world.Location;
import org.spongepowered.api.world.SerializationBehavior;
import org.spongepowered.api.world.ServerLocation;
import org.spongepowered.api.world.server.ServerWorld;
import org.spongepowered.api.world.storage.WorldProperties;
import org.spongepowered.royale.configuration.MappedConfigurationAdapter;
import org.spongepowered.royale.instance.Instance;
import org.spongepowered.royale.instance.InstanceManager;
import org.spongepowered.royale.instance.InstanceType;
import org.spongepowered.royale.instance.configuration.InstanceTypeConfiguration;
import org.spongepowered.royale.instance.exception.UnknownInstanceException;
import org.spongepowered.royale.template.ComponentTemplate;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

final class Commands {

    private static final Parameter.Value<InstanceType> INSTANCE_TYPE_PARAMETER_OPTIONAL =
            Parameter.catalogedElement(InstanceType.class).optional().setKey("instanceType").build();
    private static final Parameter.Value<InstanceType> INSTANCE_TYPE_PARAMETER =
            Parameter.catalogedElement(InstanceType.class).setKey("instanceType").build();
    private static final Parameter.Value<Boolean> FORCE_PARAMETER = Parameter.bool().setKey("force").orDefault(false).build();
    private static final Parameter.Value<Boolean> MODIFIED_PARAMETER = Parameter.bool().setKey("modified").orDefault(true).build();
    private static final Parameter.Value<SerializationBehavior> SERIALIZATION_BEHAVIOR_PARAMETER =
            Parameter.catalogedElement(SerializationBehavior.class).setKey("behavior").build();
    private static final Parameter.Value<List<ServerPlayer>> MANY_PLAYERS =
            Parameter.builder(new TypeToken<List<ServerPlayer>>() {}).parser(CatalogedValueParameters.MANY_PLAYERS).orDefault((CommandCause cause) -> {
                if (cause.root() instanceof ServerPlayer) {
                    return Collections.singletonList((ServerPlayer) cause.root());
                }
                return null;
            }).setKey("players").build();
    private static final Parameter.Value<ResourceKey> RESOURCE_KEY_ID_PARAMETER = Parameter.resourceKey().setKey("id").build();
    private static final Parameter.Value<String> NAME_OPTIONAL_PARAMETER = Parameter.string().setKey("name").build();

    private static ServerWorld getWorld(final CommandContext context) throws CommandException {
        final Optional<WorldProperties> optWorldProperties = context.getOne(CommonParameters.ONLINE_WORLD_PROPERTIES_ONLY_OPTIONAL);
        if (optWorldProperties.isPresent()) {
            return optWorldProperties.get().getWorld().orElseThrow(() -> new CommandException(
                    Component.text("World [").append(format(NamedTextColor.GREEN, optWorldProperties.get().getKey().toString()))
                            .append(Component.text("] is not online."))));
        } else if (context.getCause().getLocation().isPresent()) {
            return context.getCause().getLocation().get().getWorld();
        } else {
            throw new CommandException(Component.text("World was not provided!"));
        }
    }

    private static Command.Parameterized createCommand(final Random random, final InstanceManager instanceManager) {
        return Command.builder()
                .setPermission(Constants.Plugin.ID + ".command.create")
                .setShortDescription(Component.text("Creates an instance."))
                .setExtendedDescription(Component.text("Creates an instance from a ")
                        .append(format(NamedTextColor.GREEN, "world"))
                        .append(Component.text(" with the specified instance "))
                        .append(format(NamedTextColor.LIGHT_PURPLE, "type"))
                        .append(Component.text(".")))
                .parameter(Commands.INSTANCE_TYPE_PARAMETER_OPTIONAL)
                .parameter(CommonParameters.ONLINE_WORLD_PROPERTIES_ONLY_OPTIONAL)
                .setExecutor(context -> {
                    final InstanceType instanceType = context.getOne(Commands.INSTANCE_TYPE_PARAMETER_OPTIONAL).orElseGet(() -> {
                        final Collection<InstanceType> types = Sponge.getRegistry().getCatalogRegistry().getAllOf(InstanceType.class);
                        return Iterables.get(types, random.nextInt(types.size()));
                    });
                    final WorldProperties targetProperties = context.getOne(CommonParameters.ONLINE_WORLD_PROPERTIES_ONLY).orElseGet(() -> {
                        final Optional<WorldProperties> properties = Sponge.getServer().getWorldManager().getProperties(instanceType.getKey());
                        return properties.orElse(null);
                    });

                    if (targetProperties == null) {
                        throw new CommandException(
                                Commands.format(NamedTextColor.RED, String.format("Unable to find a world using instance type id of %s",
                                        instanceType.getKey())));
                    }

                    if (targetProperties.getKey().asString().length() > Constants.Map.MAXIMUM_WORLD_NAME_LENGTH) {
                        throw new CommandException(Component.text(String
                                .format("World name %s is too long! It must be at most %s characters!", targetProperties.getKey(),
                                        Constants.Map.MAXIMUM_WORLD_NAME_LENGTH)));
                    }

                    context.sendMessage(
                            Component.text().content("Creating an instance from [")
                                    .append(Commands.format(NamedTextColor.GREEN, targetProperties.getKey().asString()))
                                    .append(Component.text("] using instance type "))
                                    .append(Commands.format(NamedTextColor.LIGHT_PURPLE, instanceType.getName()))
                                    .append(Component.text("."))
                                    .build()
                    );

                    try {
                        instanceManager.createInstance(targetProperties.getKey(), instanceType);
                    } catch (final Exception e) {
                        throw new CommandException(Component.text(e.toString()), e);
                    }

                    context.sendMessage(
                            Component.text().content("Created instance for [")
                                    .append(Commands.format(NamedTextColor.GREEN, targetProperties.getKey().asString()))
                                    .append(Component.text("]"))
                                    .build()
                    );

                    for (final ServerPlayer player : Sponge.getServer().getOnlinePlayers()) {
                        if (player.getWorld().getKey().equals(Constants.Map.Lobby.DEFAULT_LOBBY_KEY)) {

                            player.sendMessage(Component.text().clickEvent(SpongeComponents.executeCallback(commandCause -> {
                                final Optional<Instance> inst = instanceManager.getInstance(targetProperties.getKey());
                                if (inst.isPresent()) {
                                    final ServerPlayer serverPlayer = (ServerPlayer) commandCause.root();
                                    inst.get().registerPlayer(serverPlayer);
                                    inst.get().spawnPlayer(serverPlayer);
                                }
                            })).append(LinearComponents
                                    .linear(Component.text("["), format(NamedTextColor.RED, targetProperties.getKey().toString()),
                                            Component.text("] is ready! Right-click this message or the sign to join!"))).build());
                        }
                    }
                    return CommandResult.success();
                })
                .build();
    }

    /* TODO: Dynamic Registration?
    private static Command.Parameterized registerCommand() {
        return Command.builder()
                .setPermission(Constants.Plugin.ID + ".command.register")
                .setShortDescription(Component.text("Registers an ")
                        .append(format(NamedTextColor.LIGHT_PURPLE, "instance type"))
                        .append(Component.text(".")))
                .setExtendedDescription(Component.text("Registers an ")
                        .append(format(NamedTextColor.LIGHT_PURPLE, "instance type"))
                        .append(Component.text(" using a specified ID and/or name.")))
                .parameter(Commands.RESOURCE_KEY_ID_PARAMETER)
                .parameter(Parameter.string().setKey("name").optional().build())
                .setExecutor(context -> {
                    final ResourceKey id = context.requireOne(Commands.RESOURCE_KEY_ID_PARAMETER);
                    if (Sponge.getRegistry().getCatalogRegistry().get(InstanceType.class, id).isPresent()) {
                        throw new CommandException(
                                Component.text().content("Unable to register [")
                                    .append(Commands.format(NamedTextColor.LIGHT_PURPLE, id.asString()))
                                    .append(Component.text("] as an instance with this name is already registered."))
                                    .build());
                    }

                    final String name = context.getOne(Commands.NAME_OPTIONAL_PARAMETER).orElse(null);

                    try {
                        InstanceTypeRegistryModule.getInstance().registerAdditionalCatalog(InstanceType.builder().build(id, name));
                        context.sendMessage(Component.text("Registered instance type [", format(NamedTextColor.LIGHT_PURPLE, id), "]."));
                    } catch (IOException | ObjectMappingException e) {
                        throw new CommandException(
                                Component.text("Failed to register instance [", format(NamedTextColor.LIGHT_PURPLE, id), "].", e));
                    }
                    return CommandResult.success();
                })
                .build();
    }
*/

    private static Command.Parameterized startCommand(final InstanceManager instanceManager) {
        return Command.builder()
                .setPermission(Constants.Plugin.ID + ".command.start")
                .setShortDescription(Component.text("Starts an ")
                        .append(format(NamedTextColor.LIGHT_PURPLE, "instance"))
                        .append(Component.text(".")))
                .setExtendedDescription(Component.text("Starts an ")
                        .append(format(NamedTextColor.LIGHT_PURPLE, "instance"))
                        .append(Component.text(".")))
                .parameter(CommonParameters.ONLINE_WORLD_PROPERTIES_ONLY_OPTIONAL)
                .setExecutor(context -> {
                    final ServerWorld world = Commands.getWorld(context);
                    final Optional<Instance> optInstance = instanceManager.getInstance(world.getKey());
                    if (!optInstance.isPresent() || optInstance.get().getState().equals(Instance.State.IDLE)) {
                        try {
                            context.sendMessage(
                                    Component.text().content("Starting round countdown in [")
                                        .append(Commands.format(NamedTextColor.GREEN, world.getKey().toString()))
                                        .append(Component.text("]."))
                                        .build());
                            instanceManager.startInstance(world.getKey());
                        } catch (final UnknownInstanceException e) {
                            throw new CommandException(Component.text().content("Unable to start round in [")
                                        .append(Commands.format(NamedTextColor.GREEN, world.getKey().toString()))
                                        .append(Component.text("], was it created?"))
                                        .build()
                            );
                        }
                    } else {
                        context.sendMessage(Component.text("Round already in progress."));
                    }

                    return CommandResult.success();
                })
                .build();
    }

    private static Command.Parameterized endCommand(final InstanceManager instanceManager) {
        return Command.builder()
                .setPermission(Constants.Plugin.ID + ".command.end")
                .setShortDescription(Component.text().content("Ends an ")
                        .append(Commands.format(NamedTextColor.LIGHT_PURPLE, "instance"))
                        .append(Component.text("."))
                        .build())
                .parameter(CommonParameters.ONLINE_WORLD_PROPERTIES_ONLY_OPTIONAL)
                .parameter(Commands.FORCE_PARAMETER)
                .setExecutor(context -> {
                    final Optional<WorldProperties> optWorldProperties = context.getOne(CommonParameters.ONLINE_WORLD_PROPERTIES_ONLY_OPTIONAL);
                    final ServerWorld world;
                    if (optWorldProperties.isPresent()) {
                        final Optional<ServerWorld> opt = optWorldProperties.get().getWorld();
                        if (!opt.isPresent() && instanceManager.getInstance(optWorldProperties.get().getKey()).isPresent()) {
                            context.sendMessage(
                                    Component.text(String.format("World %s was unloaded, but the instance still exists! Ending instance.",
                                            optWorldProperties.get().getKey()),
                                            NamedTextColor.YELLOW));
                            try {
                                instanceManager.endInstance(optWorldProperties.get().getKey(), true);
                            } catch (final UnknownInstanceException e) {
                                e.printStackTrace();
                            }
                            return CommandResult.empty();
                        }
                        world = opt.orElseThrow(() -> new CommandException(Component.text().content("World [")
                                .append(Commands.format(NamedTextColor.GREEN, optWorldProperties.get().getKey().toString()))
                                .append(Component.text("] is not online."))
                                .build()));
                    } else {
                        world = context.getCause().getLocation().map(Location::getWorld).orElseThrow(() ->
                                new CommandException(Component.text("World was not provided!")));
                    }

                    final boolean force = context.requireOne(Commands.FORCE_PARAMETER);

                    try {
                        context.sendMessage(Component.text()
                                .content(force ? "Forcibly e" : "E")
                                .append(Component.text("nding round in ["))
                                .append(Commands.format(NamedTextColor.GREEN, world.getKey().asString()))
                                .append(Component.text("]."))
                                .build());
                        instanceManager.endInstance(world.getKey(), force);
                    } catch (final UnknownInstanceException e) {
                        throw new CommandException(
                                Component.text().content("Unable to end round in [")
                                    .append(Commands.format(NamedTextColor.GREEN, world.getKey().asString()))
                                    .append(Component.text("]!"))
                                    .build());
                    }

                    return CommandResult.success();
                })
                .build();
    }

    private static Command.Parameterized joinCommand(final InstanceManager instanceManager) {
        return Command.builder()
                .setPermission(Constants.Plugin.ID + ".command.join")
                .setShortDescription(Component.text()
                        .content("Joins an ")
                        .append(Commands.format(NamedTextColor.LIGHT_PURPLE, "instance"))
                        .append(Component.text("."))
                        .build())
                .parameter(CommonParameters.ONLINE_WORLD_PROPERTIES_ONLY_OPTIONAL)
                .parameter(CommonParameters.PLAYER_OR_SOURCE)
                .setExecutor(context -> {
                    final ServerWorld world = Commands.getWorld(context);

                    final ServerPlayer player = context.requireOne(CommonParameters.PLAYER_OR_SOURCE);
                    final Optional<Instance> instance = instanceManager.getInstance(world.getKey());
                    if (!instance.isPresent()) {
                        throw new CommandException(
                                Component.text().content("Instance [")
                                    .append(Commands.format(NamedTextColor.GREEN, world.getKey().asString()))
                                    .append(Component.text("] is not a valid instance, is it running?"))
                                    .build());
                    }

                    player.sendMessage(Component.text().content("Joining [")
                            .append(Commands.format(NamedTextColor.GREEN, world.getKey().asString()))
                            .append(Component.text("]."))
                            .build());
                    instance.get().registerPlayer(player);
                    instance.get().spawnPlayer(player);

                    return CommandResult.success();
                })
                .build();
    }

    private static Command.Parameterized reloadCommand()  {
        return Command.builder()
                .setPermission(Constants.Plugin.ID + ".command.reload")
                .setShortDescription(Component.text()
                        .content("Reloads the configuration of an ")
                        .append(Commands.format(NamedTextColor.LIGHT_PURPLE, "instance type"))
                        .append(Component.text("."))
                        .build())
                .parameter(Commands.INSTANCE_TYPE_PARAMETER)
                .setExecutor(context -> {
                    final InstanceType instanceType = context.requireOne(Commands.INSTANCE_TYPE_PARAMETER);
                    final Path configPath = Constants.Map.INSTANCE_TYPES_FOLDER.resolve(instanceType.getKey().getValue() + ".conf");
                    final MappedConfigurationAdapter<InstanceTypeConfiguration> adapter = new MappedConfigurationAdapter<>(
                            InstanceTypeConfiguration.class, Constants.Map.DEFAULT_OPTIONS, configPath);

                    try {
                        adapter.load();
                    } catch (final IOException | ObjectMappingException e) {
                        throw new CommandException(
                                Component.text().content("Unable to load configuration for instance type [")
                                    .append(Commands.format(NamedTextColor.LIGHT_PURPLE, instanceType.getKey().asString()))
                                    .append(Component.text("]."))
                                    .build());
                    }

                    instanceType.injectFromConfig(adapter.getConfig());

                    context.sendMessage(Component.text().content("Reloaded configuration for instance type [")
                            .append(Commands.format(NamedTextColor.LIGHT_PURPLE, instanceType.getKey().asString()))
                            .append(Component.text("]."))
                            .build());

                    return CommandResult.success();
                })
                .build();
    }

    private static Command.Parameterized setSerializationCommand() {
        return Command.builder()
                .setPermission(Constants.Plugin.ID + ".command.set.serialization")
                .setShortDescription(Component.text().content("Sets the serialization property of an ")
                        .append(Commands.format(NamedTextColor.LIGHT_PURPLE, "instance"))
                        .append(Component.text("."))
                        .build())
                .parameter(CommonParameters.ONLINE_WORLD_PROPERTIES_ONLY_OPTIONAL)
                .parameter(Commands.SERIALIZATION_BEHAVIOR_PARAMETER)
                .setExecutor(context -> {
                    final ServerWorld world = Commands.getWorld(context);
                    final SerializationBehavior serializationBehavior = context.requireOne(Commands.SERIALIZATION_BEHAVIOR_PARAMETER);

                    world.getProperties().setSerializationBehavior(serializationBehavior);
                    context.sendMessage(Component.text().content("World [")
                            .append(Commands.format(NamedTextColor.GREEN, world.getKey().asString()))
                            .append(Component.text("] set to serialization behavior ["))
                            .append(Commands.format(NamedTextColor.YELLOW, serializationBehavior.getKey().asString()))
                            .append(Component.text("]."))
                            .build());

                    return CommandResult.success();
                })
                .build();
    }

    private static Command.Parameterized setCommand() {
        return Command.builder()
                .setPermission(Constants.Plugin.ID + ".command.set")
                .setShortDescription(Component.text().content("Sets a property of an ")
                        .append(Commands.format(NamedTextColor.LIGHT_PURPLE, "instance"))
                        .append(Component.text("."))
                        .build())
                .child(Commands.setSerializationCommand(), "serialization")
                .build();
    }

    private static Command.Parameterized tpWorldCommand() {
        return Command.builder()
                .setShortDescription(Component.text().content("Teleports a player to another ")
                        .append(Commands.format(NamedTextColor.GREEN, "world"))
                        .append(Component.text("."))
                        .build())
                .parameter(Commands.MANY_PLAYERS)
                .parameter(CommonParameters.ONLINE_WORLD_PROPERTIES_ONLY_OPTIONAL)
                .setPermission(Constants.Plugin.ID + ".command.tpworld")
                .setExecutor(context -> {
                    final ServerWorld world = Commands.getWorld(context);
                    for (final ServerPlayer target : context.requireOne(Commands.MANY_PLAYERS)) {
                        target.setLocation(ServerLocation.of(world, world.getProperties().getSpawnPosition()));
                    }
                    return CommandResult.success();
                })
                .build();
    }

    private static Command.Parameterized worldModifiedCommand(final InstanceManager instanceManager) {
        return Command.builder()
                .setShortDescription(Component.text("Sets whether a world has been modified"))
                .setExtendedDescription(Component.text("This controls whether or not a fast mutator pass can be used"))
                .parameter(CommonParameters.ONLINE_WORLD_PROPERTIES_ONLY)
                .parameter(Commands.MODIFIED_PARAMETER)
                .setPermission(Constants.Permissions.WORLD_MODIFIED_COMMAND)
                .setExecutor(context -> {
                    final WorldProperties properties = context.requireOne(CommonParameters.ONLINE_WORLD_PROPERTIES_ONLY);
                    final boolean modified = context.requireOne(Commands.MODIFIED_PARAMETER);
                    instanceManager.setWorldModified(properties.getKey(), modified);

                    context.sendMessage(
                            Commands.format(NamedTextColor.GREEN, String.format("Set modified state of world %s to %s!", properties.getKey(), modified)));
                    return CommandResult.success();
                })
                .build();
    }

    private static Command.Parameterized loadWorldCommand() {
        return Command.builder()
                .setShortDescription(Component.text("Manually loads a world"))
                .parameter(CommonParameters.ALL_WORLD_PROPERTIES)
                .setPermission(Constants.Permissions.WORLD_LOAD_COMMAND)
                .setExecutor(context -> {
                    final WorldProperties properties = context.requireOne(CommonParameters.ALL_WORLD_PROPERTIES);
                    if (properties.getWorld().isPresent()) {
                        throw new CommandException(Commands.format(NamedTextColor.YELLOW, String.format("World %s is already loaded!", properties.getKey())));
                    }

                    context.sendMessage(Commands.format(NamedTextColor.GREEN, String.format("Loading world %s...", properties.getKey())));
                    final CompletableFuture<ServerWorld> future = Sponge.getServer().getWorldManager().loadWorld(properties);
                    future.whenComplete((world, throwable) -> {
                        if (throwable != null) {
                            context.sendMessage(Commands.format(NamedTextColor.RED, String.format("Unable to load world %s", properties.getKey())));
                        } else {
                            context.sendMessage(Commands.format(NamedTextColor.GREEN, String.format("Successfully loaded world %s", properties.getKey())));
                        }
                    });

                    return CommandResult.success();
                })
                .build();
    }

    private static Command.Parameterized unloadWorldCommand(final InstanceManager instanceManager) {
        return Command.builder()
                .setShortDescription(Component.text("Manually unloads a world"))
                .parameter(CommonParameters.ONLINE_WORLD_PROPERTIES_ONLY)
                .setPermission(Constants.Permissions.WORLD_UNLOAD_COMMAND)
                .setExecutor(context -> {
                    final WorldProperties properties = context.requireOne(CommonParameters.ONLINE_WORLD_PROPERTIES_ONLY);

                    if (!properties.getWorld().isPresent()) {
                        throw new CommandException(Component.text(String.format("World %s is not loaded!", properties.getKey())));
                    }

                    if (instanceManager.getInstance(properties.getKey()).isPresent()) {
                        throw new CommandException(Commands.format(NamedTextColor.RED,
                                String.format("Instance %s is currently running! Use '/s end %s' to end it!", properties.getKey(),
                                        properties.getKey())));
                    }

                    context.sendMessage(Commands.format(NamedTextColor.GREEN, String.format("Unloading world %s...", properties.getKey())));
                    final CompletableFuture<Boolean> unloadFuture = Sponge.getServer().getWorldManager().unloadWorld(properties.getKey());
                    unloadFuture.whenComplete((result, throwable) -> {
                        if (throwable != null || !result) {
                            context.sendMessage(Commands.format(NamedTextColor.RED, String.format("Unable to unload world %s", properties.getKey())));
                        } else {
                            context.sendMessage(Commands.format(NamedTextColor.GREEN, String.format("Successfully unloaded world %s", properties.getKey())));
                        }
                    });
                    return CommandResult.success();
                })
                .build();
    }

    static Command.Parameterized rootCommand(final Random random, final InstanceManager instanceManager) {
        return Command.builder()
                .setPermission(Constants.Plugin.ID + ".command.help")
                .setShortDescription(Component.text("Displays available commands"))
                .setExtendedDescription(Component.text("Displays available commands")) // TODO Do this better
                .setExecutor(context -> {
                    context.sendMessage(Component.text("Some help should go here..."));
                    context.sendMessage(new ComponentTemplate("Your name is <pl_sponge:name>").parse(context.getCause().root(),
                            Collections.emptyMap()));
                    return CommandResult.success();
                })
                .child(Commands.createCommand(random, instanceManager), "create", "c")
                // .child(Commands.registerCommand(), "register", "reg")
                .child(Commands.startCommand(instanceManager), "start")
                .child(Commands.endCommand(instanceManager), "end", "e")
                .child(Commands.joinCommand(instanceManager), "join", "j")
                .child(Commands.reloadCommand(), "reload", "rel")
                .child(Commands.setCommand(), "set")
                .child(Commands.tpWorldCommand(), "tpworld", "tpw")
                .child(Commands.worldModifiedCommand(instanceManager), "worldmodified", "modified", "wm")
                .child(Commands.loadWorldCommand(), "loadworld", "load", "lw")
                .child(Commands.unloadWorldCommand(instanceManager), "unloadworld", "unload", "uw")
                .build();
    }

    private static TextComponent format(final NamedTextColor color, final String content) {
        return Component.text(content, color);
    }
}
