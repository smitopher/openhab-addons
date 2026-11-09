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
package org.openhab.binding.lutron.internal.handler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.ignoreStubs;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.openhab.binding.lutron.internal.KeypadComponent;
import org.openhab.binding.lutron.internal.discovery.project.ComponentType;
import org.openhab.binding.lutron.internal.protocol.lip.LutronCommandType;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.OpenClosedType;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.CommonTriggerEvents;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.binding.builder.ThingBuilder;

/**
 * Tests for the keypad button event handling in {@link BaseKeypadHandler}.
 *
 * @author Christopher Smit - Initial contribution
 */
@NonNullByDefault
public class BaseKeypadHandlerTest {

    private static final long WAIT_MS = 5000;
    private static final int BUTTON_1 = 1;
    private static final int CCI_1 = 25;

    private static final ThingUID THING_UID = new ThingUID("lutron", "keypad", "test");
    private static final ChannelUID BUTTON_1_UID = new ChannelUID(THING_UID, "button1");
    private static final ChannelUID CCI_1_UID = new ChannelUID(THING_UID, "cci1");

    private record Component(int id, String channel, String description,
            ComponentType type) implements KeypadComponent {
    }

    /**
     * Minimal concrete keypad handler with a fixed component layout: one button and one CCI.
     */
    @NonNullByDefault
    private static class TestKeypadHandler extends BaseKeypadHandler {
        TestKeypadHandler(Thing thing) {
            super(thing);
        }

        @Override
        protected void configureComponents(@Nullable String model) {
            buttonList.add(new Component(BUTTON_1, "button1", "Button 1", ComponentType.BUTTON));
            cciList.add(new Component(CCI_1, "cci1", "CCI 1", ComponentType.CCI));
        }

        @Override
        protected boolean isButton(int id) {
            return id == BUTTON_1;
        }

        @Override
        protected boolean isCCI(int id) {
            return id == CCI_1;
        }

        @Override
        protected boolean isLed(int id) {
            return false;
        }
    }

    private @NonNullByDefault({}) Thing thing;
    private @NonNullByDefault({}) ThingHandlerCallback callback;
    private @NonNullByDefault({}) TestKeypadHandler handler;
    private boolean initialized;

    @BeforeEach
    public void setUp() {
        thing = ThingBuilder.create(new ThingTypeUID("lutron", "keypad"), THING_UID)
                .withConfiguration(new Configuration(Map.of("integrationId", 1))).build();
        callback = mock(ThingHandlerCallback.class);
        handler = new TestKeypadHandler(thing);
        handler.setCallback(callback);
        initialized = false;
    }

    /**
     * Initializes the handler on first use, so that tests can set the handler's flags first, and waits for the
     * channels to be created. Interactions from the initialization are cleared.
     */
    private void initializeOnce() {
        if (initialized) {
            return;
        }
        initialized = true;
        handler.initialize();
        verify(callback, timeout(WAIT_MS)).thingUpdated(any());
        thing = handler.getThing();
        clearInvocations(callback);
    }

    private void receiveDeviceUpdate(int component, int action) {
        initializeOnce();
        handler.handleUpdate(LutronCommandType.DEVICE, String.valueOf(component), String.valueOf(action));
    }

    @Test
    public void pressFiresPressedEvent() {
        receiveDeviceUpdate(BUTTON_1, 3);
        verify(callback).channelTriggered(thing, BUTTON_1_UID, CommonTriggerEvents.PRESSED);
        verifyNoMoreTriggersOrStates();
    }

    @Test
    public void releaseFiresReleasedEvent() {
        receiveDeviceUpdate(BUTTON_1, 4);
        verify(callback).channelTriggered(thing, BUTTON_1_UID, CommonTriggerEvents.RELEASED);
        verifyNoMoreTriggersOrStates();
    }

    @Test
    public void holdFiresLongPressedThenReleased() {
        receiveDeviceUpdate(BUTTON_1, 5);
        InOrder inOrder = inOrder(callback);
        inOrder.verify(callback).channelTriggered(thing, BUTTON_1_UID, CommonTriggerEvents.LONG_PRESSED);
        inOrder.verify(callback).channelTriggered(thing, BUTTON_1_UID, CommonTriggerEvents.RELEASED);
        verifyNoMoreTriggersOrStates();
    }

    @Test
    public void doubleTapFiresDoublePressedEvent() {
        receiveDeviceUpdate(BUTTON_1, 6);
        verify(callback).channelTriggered(thing, BUTTON_1_UID, CommonTriggerEvents.DOUBLE_PRESSED);
        verifyNoMoreTriggersOrStates();
    }

    @Test
    public void holdReleaseFiresReleasedEvent() {
        receiveDeviceUpdate(BUTTON_1, 32);
        verify(callback).channelTriggered(thing, BUTTON_1_UID, CommonTriggerEvents.RELEASED);
        verifyNoMoreTriggersOrStates();
    }

    @Test
    public void cciUpdatesContactStateWithoutFiringEvents() {
        receiveDeviceUpdate(CCI_1, 3);
        verify(callback).stateUpdated(CCI_1_UID, OpenClosedType.CLOSED);
        receiveDeviceUpdate(CCI_1, 4);
        verify(callback).stateUpdated(CCI_1_UID, OpenClosedType.OPEN);
        verify(callback, never()).channelTriggered(any(), any(), any());
    }

    @Test
    public void holdOnCciIsIgnored() {
        receiveDeviceUpdate(CCI_1, 5);
        verifyNoInteractions(callback);
    }

    @Test
    public void unknownComponentIsIgnored() {
        receiveDeviceUpdate(99, 3);
        verifyNoInteractions(callback);
    }

    @Test
    public void switchModePressWithAutoReleaseUpdatesOnThenOff() {
        handler.useTriggerChannels = false;
        handler.autoRelease = true;
        receiveDeviceUpdate(BUTTON_1, 3);
        InOrder inOrder = inOrder(callback);
        inOrder.verify(callback).stateUpdated(BUTTON_1_UID, OnOffType.ON);
        inOrder.verify(callback).stateUpdated(BUTTON_1_UID, OnOffType.OFF);
    }

    @Test
    public void switchModeHoldSignalsRelease() {
        handler.useTriggerChannels = false;
        handler.autoRelease = false;
        receiveDeviceUpdate(BUTTON_1, 5);
        verify(callback).stateUpdated(BUTTON_1_UID, OnOffType.OFF);
        verify(callback, never()).channelTriggered(any(), any(), any());
    }

    private void verifyNoMoreTriggersOrStates() {
        verifyNoMoreInteractions(ignoreStubs(callback));
    }
}
