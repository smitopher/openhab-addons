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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.lutron.internal.KeypadComponent;
import org.openhab.binding.lutron.internal.discovery.project.ComponentType;
import org.openhab.binding.lutron.internal.keypad.KeypadChannels.ChannelSpec;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.OpenClosedType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.thing.type.ChannelKind;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.openhab.core.types.State;

/**
 * Tests for {@link KeypadChannels}, which does not depend on a Lutron protocol.
 *
 * @author Christopher Smith - Initial contribution
 */
@NonNullByDefault
public class KeypadChannelsTest {
    private static final ThingUID THING_UID = new ThingUID("lutron:keypad:test:keypad");
    private static final ChannelTypeUID TRIGGER_TYPE = new ChannelTypeUID("lutron", "buttonTrigger");
    private static final ChannelTypeUID SWITCH_TYPE = new ChannelTypeUID("lutron", "button");
    private static final ChannelTypeUID LED_TYPE = new ChannelTypeUID("lutron", "ledIndicator");
    private static final ChannelTypeUID CCI_TYPE = new ChannelTypeUID("lutron", "cciState");

    private static final int BUTTON = 1;
    private static final int LED = 81;
    private static final int CCI = 30;

    private record Component(int id, String channel, String description,
            ComponentType type) implements KeypadComponent {
    }

    private static final List<KeypadComponent> COMPONENTS = List.of(
            new Component(BUTTON, "button1", "Button 1", ComponentType.BUTTON),
            new Component(LED, "led1", "LED 1", ComponentType.LED),
            new Component(CCI, "cci1", "CCI 1", ComponentType.CCI),
            new Component(90, "scene1", "Scene 1", ComponentType.SCENE_CONTROLLER));

    private static class Host implements KeypadChannels.Host {
        Thing thing = ThingBuilder.create(new ThingTypeUID("lutron", "keypad"), THING_UID)
                .withConfiguration(new Configuration(Map.of())).build();
        int thingUpdates;
        final List<String> events = new ArrayList<>();

        @Override
        public Thing getThing() {
            return thing;
        }

        @Override
        public ThingBuilder editThing() {
            return ThingBuilder.create(thing.getThingTypeUID(), thing.getUID()).withChannels(thing.getChannels());
        }

        @Override
        public void updateThing(Thing thing) {
            this.thing = thing;
            thingUpdates++;
        }

        @Override
        public void triggerChannel(ChannelUID channelUID, String event) {
            events.add(channelUID.getId() + ":" + event);
        }

        @Override
        public void updateState(ChannelUID channelUID, State state) {
            events.add(channelUID.getId() + "=" + state);
        }
    }

    private final Host host = new Host();

    private KeypadChannels channels(boolean triggerButtons, boolean releaseAfterHold) {
        KeypadChannels channels = new KeypadChannels(host,
                Map.of(ComponentType.BUTTON,
                        triggerButtons ? ChannelSpec.trigger(TRIGGER_TYPE) : ChannelSpec.state(SWITCH_TYPE, "Switch"),
                        ComponentType.LED, ChannelSpec.state(LED_TYPE, "Switch"), ComponentType.CCI,
                        ChannelSpec.state(CCI_TYPE, "Contact")),
                releaseAfterHold);
        channels.configure(COMPONENTS, List.of());
        return channels;
    }

    @Test
    public void createsChannelsInComponentOrderAndSkipsTypesWithoutASpec() {
        channels(true, true);

        List<Channel> created = host.thing.getChannels();
        assertEquals(3, created.size());
        assertEquals(List.of("button1", "led1", "cci1"), created.stream().map(c -> c.getUID().getId()).toList());
        Channel button = created.get(0);
        assertEquals(ChannelKind.TRIGGER, button.getKind());
        assertEquals(TRIGGER_TYPE, button.getChannelTypeUID());
        assertNull(button.getAcceptedItemType());
        assertEquals("Button 1", button.getLabel());
        Channel cci = created.get(2);
        assertEquals(ChannelKind.STATE, cci.getKind());
        assertEquals(CCI_TYPE, cci.getChannelTypeUID());
        assertEquals("Contact", cci.getAcceptedItemType());
    }

    @Test
    public void channelsAreRebuiltOnlyWhenTheyDiffer() {
        KeypadChannels channels = channels(true, true);
        assertEquals(1, host.thingUpdates);

        channels.configure(COMPONENTS, List.of());
        assertEquals(1, host.thingUpdates);

        // A different channel type for the same channel ids is a change
        channels(false, true);
        assertEquals(2, host.thingUpdates);
        assertEquals(SWITCH_TYPE, host.thing.getChannels().get(0).getChannelTypeUID());
    }

    @Test
    public void extraChannelsFollowTheComponentChannels() {
        KeypadChannels channels = channels(true, true);
        Channel extra = ChannelBuilder.create(new ChannelUID(THING_UID, "press"), "String").build();

        channels.configure(COMPONENTS, List.of(extra));

        assertEquals(4, host.thing.getChannels().size());
        assertEquals("press", host.thing.getChannels().get(3).getUID().getId());
    }

    @Test
    public void looksUpChannelsAndComponents() {
        KeypadChannels channels = channels(true, true);

        ChannelUID channel = channels.channelFor(LED);
        assertNotNull(channel);
        assertEquals("led1", channel.getId());
        assertEquals(LED, channels.componentFor(channel));
        assertNull(channels.channelFor(90));
        assertNull(channels.componentFor(new ChannelUID(THING_UID, "unknown")));
    }

    @Test
    public void triggerButtonsFireEventsAndCanAddAReleaseAfterAHold() {
        KeypadChannels channels = channels(true, true);

        for (ButtonEvent event : ButtonEvent.values()) {
            channels.buttonEvent(BUTTON, event, true);
        }

        assertEquals(List.of("button1:PRESSED", "button1:RELEASED", "button1:LONG_PRESSED", "button1:RELEASED",
                "button1:DOUBLE_PRESSED", "button1:RELEASED"), host.events);
    }

    @Test
    public void triggerButtonsWithoutReleaseAfterHoldPassEventsThrough() {
        KeypadChannels channels = channels(true, false);

        channels.buttonEvent(BUTTON, ButtonEvent.PRESS, false);
        channels.buttonEvent(BUTTON, ButtonEvent.HOLD, false);
        channels.buttonEvent(BUTTON, ButtonEvent.RELEASE, false);

        assertEquals(List.of("button1:PRESSED", "button1:LONG_PRESSED", "button1:RELEASED"), host.events);
    }

    @Test
    public void switchButtonsTurnOnAndOff() {
        KeypadChannels channels = channels(false, true);

        channels.buttonEvent(BUTTON, ButtonEvent.PRESS, true);
        channels.buttonEvent(BUTTON, ButtonEvent.PRESS, false);
        channels.buttonEvent(BUTTON, ButtonEvent.RELEASE, false);
        channels.buttonEvent(BUTTON, ButtonEvent.HOLD, false);
        channels.buttonEvent(BUTTON, ButtonEvent.DOUBLE_TAP, false);
        channels.buttonEvent(BUTTON, ButtonEvent.HOLD_RELEASE, false);

        assertEquals(
                List.of("button1=" + OnOffType.ON, "button1=" + OnOffType.OFF, "button1=" + OnOffType.ON,
                        "button1=" + OnOffType.OFF, "button1=" + OnOffType.OFF, "button1=" + OnOffType.OFF),
                host.events);
    }

    @Test
    public void ccisCloseOnPressAndOpenOnReleaseOnly() {
        KeypadChannels channels = channels(true, true);

        for (ButtonEvent event : ButtonEvent.values()) {
            channels.buttonEvent(CCI, event, true);
        }

        assertEquals(List.of("cci1=" + OpenClosedType.CLOSED, "cci1=" + OpenClosedType.OPEN), host.events);
    }

    @Test
    public void ledsIgnoreButtonEventsAndTakeStates() {
        KeypadChannels channels = channels(true, true);

        assertTrue(channels.buttonEvent(LED, ButtonEvent.PRESS, true));
        assertTrue(channels.update(LED, new StringType("ON")));

        assertEquals(List.of("led1=ON"), host.events);
    }

    @Test
    public void unknownComponentsAreReported() {
        KeypadChannels channels = channels(true, true);

        assertFalse(channels.buttonEvent(99, ButtonEvent.PRESS, true));
        assertFalse(channels.update(99, new StringType("ON")));
        assertTrue(host.events.isEmpty());
    }

    @Test
    public void eventsBeforeConfigurationAreReported() {
        KeypadChannels channels = new KeypadChannels(host, Map.of(), true);

        assertFalse(channels.buttonEvent(BUTTON, ButtonEvent.PRESS, true));
        assertTrue(host.events.isEmpty());
    }
}
