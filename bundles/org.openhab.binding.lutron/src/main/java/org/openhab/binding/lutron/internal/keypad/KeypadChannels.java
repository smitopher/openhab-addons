/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.lutron.internal.keypad;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.lutron.internal.KeypadComponent;
import org.openhab.binding.lutron.internal.discovery.project.ComponentType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.OpenClosedType;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.thing.type.ChannelKind;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.openhab.core.types.State;

/**
 * Creates the channels of a keypad from its list of components, and delivers button events, CCI changes and LED
 * states to them.
 *
 * This class does not depend on a Lutron protocol. A handler provides the access to its thing through {@link Host},
 * chooses the channel types through {@link ChannelSpec}, and keeps the protocol specific parts, such as parsing
 * messages and sending commands, to itself.
 *
 * @author Christopher Smith - Initial contribution
 */
@NonNullByDefault
public final class KeypadChannels {

    /**
     * The operations of the owning handler that this class needs.
     */
    public interface Host {
        Thing getThing();

        ThingBuilder editThing();

        void updateThing(Thing thing);

        void triggerChannel(ChannelUID channelUID, String event);

        void updateState(ChannelUID channelUID, State state);
    }

    /**
     * The channel created for one type of component.
     *
     * @param type the channel type
     * @param itemType the accepted item type of a state channel, or {@code null} for a trigger channel
     */
    public record ChannelSpec(ChannelTypeUID type, @Nullable String itemType) {
        public static ChannelSpec trigger(ChannelTypeUID type) {
            return new ChannelSpec(type, null);
        }

        public static ChannelSpec state(ChannelTypeUID type, String itemType) {
            return new ChannelSpec(type, itemType);
        }

        public boolean isTrigger() {
            return itemType == null;
        }
    }

    private record Mapping(Map<Integer, ChannelUID> channels, Map<ChannelUID, Integer> components,
            Map<Integer, ComponentType> types) {
    }

    private final Host host;
    private final Map<ComponentType, ChannelSpec> specs;
    private final boolean releaseAfterHold;

    // Replaced as a whole, so that events arriving during configure() see either the old or the new mapping
    private volatile Mapping mapping = new Mapping(Map.of(), Map.of(), Map.of());

    /**
     * @param specs the channel to create for each type of component; components of other types get no channel
     * @param releaseAfterHold whether a hold fires a release event after the long press event, for systems that
     *            report no release after a hold
     */
    public KeypadChannels(Host host, Map<ComponentType, ChannelSpec> specs, boolean releaseAfterHold) {
        this.host = host;
        this.specs = Map.copyOf(specs);
        this.releaseAfterHold = releaseAfterHold;
    }

    /**
     * Makes the thing have a channel for every component, in the order of the components, followed by the extra
     * channels. The thing is only updated if its channels differ.
     */
    public void configure(List<KeypadComponent> components, List<Channel> extraChannels) {
        Thing thing = host.getThing();
        List<Channel> channels = new ArrayList<>();
        Map<Integer, ChannelUID> componentChannels = new HashMap<>();
        Map<ChannelUID, Integer> channelComponents = new HashMap<>();
        Map<Integer, ComponentType> componentTypes = new HashMap<>();

        for (KeypadComponent component : components) {
            ChannelSpec spec = specs.get(component.type());
            if (spec == null) {
                continue;
            }
            ChannelUID channelUID = new ChannelUID(thing.getUID(), component.channel());
            String itemType = spec.itemType();
            if (itemType == null) {
                // Physical keypad buttons are momentary events, modeled as trigger channels. An item can still be
                // linked to them via a trigger profile (e.g. system:rawbutton-toggle-switch).
                channels.add(ChannelBuilder.create(channelUID).withType(spec.type()).withKind(ChannelKind.TRIGGER)
                        .withLabel(component.description()).build());
            } else {
                channels.add(ChannelBuilder.create(channelUID, itemType).withType(spec.type())
                        .withLabel(component.description()).build());
            }
            componentChannels.put(component.id(), channelUID);
            channelComponents.put(channelUID, component.id());
            componentTypes.put(component.id(), component.type());
        }
        channels.addAll(extraChannels);

        mapping = new Mapping(Map.copyOf(componentChannels), Map.copyOf(channelComponents), Map.copyOf(componentTypes));
        if (!sameChannels(thing.getChannels(), channels)) {
            host.updateThing(host.editThing().withChannels(channels).build());
        }
    }

    private static boolean sameChannels(List<Channel> existing, List<Channel> wanted) {
        if (existing.size() != wanted.size()) {
            return false;
        }
        for (int i = 0; i < existing.size(); i++) {
            Channel a = existing.get(i);
            Channel b = wanted.get(i);
            if (!a.getUID().equals(b.getUID()) || !Objects.equals(a.getChannelTypeUID(), b.getChannelTypeUID())
                    || a.getKind() != b.getKind() || !Objects.equals(a.getAcceptedItemType(), b.getAcceptedItemType())
                    || !Objects.equals(a.getLabel(), b.getLabel())) {
                return false;
            }
        }
        return true;
    }

    public @Nullable ChannelUID channelFor(int componentId) {
        return mapping.channels().get(componentId);
    }

    public @Nullable Integer componentFor(ChannelUID channelUID) {
        return mapping.components().get(channelUID);
    }

    /**
     * Delivers a button action to the channel of a component.
     *
     * A button with a trigger channel fires events. A button with a Switch channel turns ON when pressed, and OFF
     * when released, at the end of a hold, or right after the press if {@code autoRelease} is set. A CCI closes when
     * pressed and opens when released.
     *
     * @param autoRelease whether a Switch button turns OFF again right after it is pressed
     * @return whether the keypad has a channel for the component
     */
    public boolean buttonEvent(int componentId, ButtonEvent event, boolean autoRelease) {
        Mapping current = mapping;
        ChannelUID channelUID = current.channels().get(componentId);
        ComponentType type = current.types().get(componentId);
        if (channelUID == null || type == null) {
            return false;
        }
        if (type == ComponentType.CCI) {
            if (event == ButtonEvent.PRESS) {
                host.updateState(channelUID, OpenClosedType.CLOSED);
            } else if (event == ButtonEvent.RELEASE) {
                host.updateState(channelUID, OpenClosedType.OPEN);
            }
        } else if (type == ComponentType.BUTTON) {
            ChannelSpec spec = specs.get(type);
            if (spec != null && spec.isTrigger()) {
                for (String triggerEvent : event.triggerEvents(releaseAfterHold)) {
                    host.triggerChannel(channelUID, triggerEvent);
                }
            } else if (event == ButtonEvent.PRESS) {
                host.updateState(channelUID, OnOffType.ON);
                if (autoRelease) {
                    host.updateState(channelUID, OnOffType.OFF);
                }
            } else if (event != ButtonEvent.DOUBLE_TAP) {
                // Lutron sends no release after a hold, so a hold also ends the press
                host.updateState(channelUID, OnOffType.OFF);
            }
        }
        return true;
    }

    /**
     * Updates the state channel of a component, such as an LED.
     *
     * @return whether the keypad has a channel for the component
     */
    public boolean update(int componentId, State state) {
        ChannelUID channelUID = mapping.channels().get(componentId);
        if (channelUID == null) {
            return false;
        }
        host.updateState(channelUID, state);
        return true;
    }
}
