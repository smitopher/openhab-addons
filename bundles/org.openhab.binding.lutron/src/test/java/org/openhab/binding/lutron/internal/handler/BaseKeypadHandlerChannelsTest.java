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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.openhab.binding.lutron.internal.protocol.LutronCommandNew;
import org.openhab.binding.lutron.internal.protocol.lip.LutronCommandType;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.CommonTriggerEvents;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.types.RefreshType;

/**
 * Characterization tests for the channels that {@link BaseKeypadHandler} subclasses create and for their LED
 * handling, through the real initialization path. They pin down the current behavior so that moving the channel and
 * event handling into shared code can be checked to change nothing.
 *
 * @author Christopher Smith - Initial contribution
 */
@NonNullByDefault
public class BaseKeypadHandlerChannelsTest {
    private static final long WAIT_MS = 5000;
    private static final ThingUID BRIDGE_UID = new ThingUID("lutron", "ipbridge", "test");
    private static final int INTEGRATION_ID = 10;

    private final ThingHandlerCallback callback = mock(ThingHandlerCallback.class);
    private final Bridge bridge = mock(Bridge.class);
    private final LutronBridgeHandler bridgeHandler = mock(LutronBridgeHandler.class);

    @BeforeEach
    public void setUp() {
        when(callback.getBridge(BRIDGE_UID)).thenReturn(bridge);
        when(bridge.getHandler()).thenReturn(bridgeHandler);
        // An offline bridge makes initDeviceState() end with a status update that the tests can wait for
        when(bridge.getStatus()).thenReturn(ThingStatus.OFFLINE);
    }

    private static Thing thing(String type, Map<String, Object> config) {
        ThingUID uid = new ThingUID("lutron", type, "test");
        return ThingBuilder.create(new ThingTypeUID("lutron", type), uid).withBridge(BRIDGE_UID)
                .withConfiguration(new Configuration(config)).build();
    }

    /**
     * Initializes the handler and waits until its asynchronous initialization has finished.
     */
    private Thing initialize(BaseKeypadHandler handler) {
        handler.setCallback(callback);
        handler.initialize();
        verify(callback, timeout(WAIT_MS)).statusUpdated(any(),
                argThat(info -> info.getStatusDetail() == ThingStatusDetail.BRIDGE_OFFLINE));
        ArgumentCaptor<Thing> updated = ArgumentCaptor.forClass(Thing.class);
        verify(callback, atLeastOnce()).thingUpdated(updated.capture());
        return updated.getValue();
    }

    private static List<String> describe(Thing thing) {
        return thing.getChannels().stream().map(BaseKeypadHandlerChannelsTest::describe).collect(Collectors.toList());
    }

    private static String describe(Channel channel) {
        return channel.getUID().getId() + "|" + channel.getKind() + "|" + channel.getChannelTypeUID() + "|"
                + channel.getAcceptedItemType() + "|" + channel.getLabel();
    }

    private List<String> sentCommands() {
        ArgumentCaptor<LutronCommandNew> sent = ArgumentCaptor.forClass(LutronCommandNew.class);
        verify(bridgeHandler, atLeastOnce()).sendCommand(sent.capture());
        return sent.getAllValues().stream().map(Object::toString).collect(Collectors.toList());
    }

    @Test
    public void triggerKeypadHasButtonsThenLedsThenCcis() {
        Thing thing = initialize(new VcrxHandler(thing("vcrx", Map.of("integrationId", INTEGRATION_ID))));

        assertEquals(
                List.of("button1|TRIGGER|lutron:buttonTrigger|null|Button 1",
                        "button2|TRIGGER|lutron:buttonTrigger|null|Button 2",
                        "button3|TRIGGER|lutron:buttonTrigger|null|Button 3",
                        "button4|TRIGGER|lutron:buttonTrigger|null|Button 4",
                        "button5|TRIGGER|lutron:buttonTrigger|null|Button 5",
                        "button6|TRIGGER|lutron:buttonTrigger|null|Button 6",
                        "led1|STATE|lutron:ledIndicator|Switch|LED 1", "led2|STATE|lutron:ledIndicator|Switch|LED 2",
                        "led3|STATE|lutron:ledIndicator|Switch|LED 3", "led4|STATE|lutron:ledIndicator|Switch|LED 4",
                        "led5|STATE|lutron:ledIndicator|Switch|LED 5", "led6|STATE|lutron:ledIndicator|Switch|LED 6",
                        "cci1|STATE|lutron:cciState|Contact|CCI 1", "cci2|STATE|lutron:cciState|Contact|CCI 2",
                        "cci3|STATE|lutron:cciState|Contact|CCI 3", "cci4|STATE|lutron:cciState|Contact|CCI 4"),
                describe(thing));
    }

    @Test
    public void picoButtonsUseThePicoTriggerType() {
        Thing thing = initialize(
                new PicoKeypadHandler(thing("pico", Map.of("integrationId", INTEGRATION_ID, "model", "2B"))));

        assertEquals(List.of("button1|TRIGGER|lutron:picoButtonTrigger|null|Button 1",
                "button3|TRIGGER|lutron:picoButtonTrigger|null|Button 3"), describe(thing));
    }

    @Test
    public void virtualKeypadButtonsAreAdvancedSwitchChannels() {
        Thing thing = initialize(new VirtualKeypadHandler(thing("virtualkeypad", Map.of("integrationId", 1))));

        List<String> channels = describe(thing);
        assertEquals(200, channels.size());
        assertEquals("button1|STATE|lutron:buttonAdvanced|Switch|Virtual Button", channels.get(0));
        assertEquals("button100|STATE|lutron:buttonAdvanced|Switch|Virtual Button", channels.get(99));
        assertEquals("led1|STATE|lutron:ledIndicatorAdvanced|Switch|Virtual LED", channels.get(100));
        assertEquals("led100|STATE|lutron:ledIndicatorAdvanced|Switch|Virtual LED", channels.get(199));
    }

    @Test
    public void casetaVirtualKeypadHasNoLeds() {
        Thing thing = initialize(
                new VirtualKeypadHandler(thing("virtualkeypad", Map.of("integrationId", 1, "model", "Caseta"))));

        assertEquals(100, describe(thing).size());
    }

    @Test
    public void ledStateUpdatesSetTheLedSwitch() {
        VcrxHandler handler = new VcrxHandler(thing("vcrx", Map.of("integrationId", INTEGRATION_ID)));
        Thing thing = initialize(handler);

        handler.handleUpdate(LutronCommandType.DEVICE, "81", "9", "1");
        handler.handleUpdate(LutronCommandType.DEVICE, "82", "9", "0");

        verify(callback).stateUpdated(new ChannelUID(thing.getUID(), "led1"), OnOffType.ON);
        verify(callback).stateUpdated(new ChannelUID(thing.getUID(), "led2"), OnOffType.OFF);
    }

    @Test
    public void ledCommandsAndRefreshAreSentToTheBridge() {
        VcrxHandler handler = new VcrxHandler(thing("vcrx", Map.of("integrationId", INTEGRATION_ID)));
        Thing thing = initialize(handler);

        handler.handleCommand(new ChannelUID(thing.getUID(), "led1"), OnOffType.ON);
        handler.handleCommand(new ChannelUID(thing.getUID(), "led2"), OnOffType.OFF);
        handler.handleCommand(new ChannelUID(thing.getUID(), "led3"), RefreshType.REFRESH);

        assertEquals(List.of("#DEVICE,10,81,9,1", "#DEVICE,10,82,9,0", "?DEVICE,10,83,9"), sentCommands());
    }

    @Test
    public void linkingAnLedQueriesIt() {
        VcrxHandler handler = new VcrxHandler(thing("vcrx", Map.of("integrationId", INTEGRATION_ID)));
        Thing thing = initialize(handler);

        handler.channelLinked(new ChannelUID(thing.getUID(), "led4"));

        assertEquals(List.of("?DEVICE,10,84,9"), sentCommands());
    }

    @Test
    public void linkingAVirtualButtonSetsItOff() {
        VirtualKeypadHandler handler = new VirtualKeypadHandler(thing("virtualkeypad", Map.of("integrationId", 1)));
        Thing thing = initialize(handler);

        handler.channelLinked(new ChannelUID(thing.getUID(), "button5"));

        verify(callback).stateUpdated(new ChannelUID(thing.getUID(), "button5"), OnOffType.OFF);
    }

    @Test
    public void virtualButtonCommandsPressAndRelease() {
        VirtualKeypadHandler handler = new VirtualKeypadHandler(thing("virtualkeypad", Map.of("integrationId", 1)));
        Thing thing = initialize(handler);

        handler.handleCommand(new ChannelUID(thing.getUID(), "button5"), OnOffType.ON);

        assertEquals(List.of("#DEVICE,1,5,3", "#DEVICE,1,5,4"), sentCommands());
    }

    @Test
    public void commandsOnTriggerButtonsAreIgnored() {
        VcrxHandler handler = new VcrxHandler(thing("vcrx", Map.of("integrationId", INTEGRATION_ID)));
        Thing thing = initialize(handler);

        handler.handleCommand(new ChannelUID(thing.getUID(), "button1"), OnOffType.ON);

        verify(bridgeHandler, never()).sendCommand(any());
    }

    @Test
    public void buttonEventsReachTheInitializedChannels() {
        VcrxHandler handler = new VcrxHandler(thing("vcrx", Map.of("integrationId", INTEGRATION_ID)));
        Thing thing = initialize(handler);

        handler.handleUpdate(LutronCommandType.DEVICE, "2", "3");

        verify(callback).channelTriggered(any(), argThat(uid -> uid.equals(new ChannelUID(thing.getUID(), "button2"))),
                argThat(CommonTriggerEvents.PRESSED::equals));
    }
}
