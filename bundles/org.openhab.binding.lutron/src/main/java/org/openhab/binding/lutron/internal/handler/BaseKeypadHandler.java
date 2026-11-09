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

import static org.openhab.binding.lutron.internal.LutronBindingConstants.BINDING_ID;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.lutron.internal.KeypadComponent;
import org.openhab.binding.lutron.internal.discovery.project.ComponentType;
import org.openhab.binding.lutron.internal.keypad.ButtonEvent;
import org.openhab.binding.lutron.internal.keypad.KeypadChannels;
import org.openhab.binding.lutron.internal.keypad.KeypadChannels.ChannelSpec;
import org.openhab.binding.lutron.internal.keypadconfig.KeypadConfig;
import org.openhab.binding.lutron.internal.protocol.DeviceCommand;
import org.openhab.binding.lutron.internal.protocol.lip.LutronCommandType;
import org.openhab.binding.lutron.internal.protocol.lip.TargetType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract class providing common definitions and methods for derived keypad classes
 *
 * @author Bob Adair - Initial contribution, based partly on Allan Tong's KeypadHandler class
 */
public abstract class BaseKeypadHandler extends LutronHandler {
    private final Logger logger = LoggerFactory.getLogger(BaseKeypadHandler.class);

    protected List<KeypadComponent> buttonList = new ArrayList<>();
    protected List<KeypadComponent> ledList = new ArrayList<>();
    protected List<KeypadComponent> cciList = new ArrayList<>();

    Map<Integer, Integer> leapButtonMap;
    Map<Integer, Integer> leapButtonInverseMap;

    protected int integrationId;
    protected String model;
    protected Boolean autoRelease;
    protected Boolean advancedChannels = false;
    protected boolean useTriggerChannels = true;
    protected String triggerChannelTypeId = "buttonTrigger";

    protected abstract void configureComponents(String model);

    private final Object asyncInitLock = new Object();

    protected KeypadConfig kp;

    // Replaced by configureChannels() once the components are known
    private volatile KeypadChannels channels = new KeypadChannels(new ChannelsHost(), Map.of(), true);
    protected TargetType commandTargetType = TargetType.KEYPAD; // For LEAP bridge

    public BaseKeypadHandler(Thing thing) {
        super(thing);
    }

    private class ChannelsHost implements KeypadChannels.Host {
        @Override
        public Thing getThing() {
            return BaseKeypadHandler.this.getThing();
        }

        @Override
        public ThingBuilder editThing() {
            return BaseKeypadHandler.this.editThing();
        }

        @Override
        public void updateThing(Thing thing) {
            BaseKeypadHandler.this.updateThing(thing);
        }

        @Override
        public void triggerChannel(ChannelUID channelUID, String event) {
            BaseKeypadHandler.this.triggerChannel(channelUID, event);
        }

        @Override
        public void updateState(ChannelUID channelUID, State state) {
            BaseKeypadHandler.this.updateState(channelUID, state);
        }
    }

    /**
     * Determine if keypad component with the specified id is a LED. Keypad handlers which do not use a KeypadConfig
     * object must override this to provide their own test.
     *
     * @param id The component id.
     * @return True if the component is a LED.
     */
    protected boolean isLed(int id) {
        return kp.isLed(id);
    }

    /**
     * Determine if keypad component with the specified id is a button. Keypad handlers which do not use a KeypadConfig
     * object must override this to provide their own test.
     *
     * @param id The component id.
     * @return True if the component is a button.
     */
    protected boolean isButton(int id) {
        return kp.isButton(id);
    }

    /**
     * Determine if keypad component with the specified id is a CCI. Keypad handlers which do not use a KeypadConfig
     * object must override this to provide their own test.
     *
     * @param id The component id.
     * @return True if the component is a CCI.
     */
    protected boolean isCCI(int id) {
        return kp.isCCI(id);
    }

    protected void configureChannels() {
        logger.debug("Configuring channels for keypad {}", integrationId);

        Map<ComponentType, ChannelSpec> specs = new EnumMap<>(ComponentType.class);
        if (useTriggerChannels) {
            specs.put(ComponentType.BUTTON, ChannelSpec.trigger(new ChannelTypeUID(BINDING_ID, triggerChannelTypeId)));
        } else {
            // Virtual keypad buttons are bidirectional and stay Switch state channels.
            specs.put(ComponentType.BUTTON, ChannelSpec
                    .state(new ChannelTypeUID(BINDING_ID, advancedChannels ? "buttonAdvanced" : "button"), "Switch"));
        }
        specs.put(ComponentType.LED, ChannelSpec.state(
                new ChannelTypeUID(BINDING_ID, advancedChannels ? "ledIndicatorAdvanced" : "ledIndicator"), "Switch"));
        // CCIs for VCRX or eventually HomeWorks CCI
        specs.put(ComponentType.CCI, ChannelSpec.state(new ChannelTypeUID(BINDING_ID, "cciState"), "Contact"));

        // A hold-programmed button reports a hold instead of a release, so a release follows the long press event
        // to keep trigger profiles from getting stuck in the pressed state. Systems that also report a hold release
        // fire a harmless duplicate release.
        KeypadChannels newChannels = new KeypadChannels(new ChannelsHost(), specs, true);
        List<KeypadComponent> components = new ArrayList<>(buttonList);
        components.addAll(ledList);
        components.addAll(cciList);
        newChannels.configure(components, List.of());
        channels = newChannels;
        logger.debug("Done configuring channels for keypad {}", integrationId);
    }

    protected ChannelUID channelFromComponent(int component) {
        ChannelUID channelUID = channels.channelFor(component);
        if (channelUID == null) {
            logger.debug("Unknown component {}", component);
        }
        return channelUID;
    }

    protected Integer componentFromChannel(ChannelUID channelUID) {
        return channels.componentFor(channelUID);
    }

    @Override
    public int getIntegrationId() {
        return integrationId;
    }

    @Override
    public void initialize() {
        Number id = (Number) getThing().getConfiguration().get("integrationId");
        if (id == null) {
            updateStatus(ThingStatusDetail.CONFIGURATION_ERROR, "No integrationId");
            return;
        }
        integrationId = id.intValue();

        logger.debug("Initializing Keypad Handler for integration ID {}", id);

        model = (String) getThing().getConfiguration().get("model");
        if (model != null) {
            model = model.toUpperCase();
            if (model.contains("-")) {
                // strip off system prefix if model is of the form "system-model"
                String[] modelSplit = model.split("-", 2);
                model = modelSplit[1];
            }
        }

        if (!useTriggerChannels) {
            Boolean arParam = (Boolean) getThing().getConfiguration().get("autorelease");
            autoRelease = arParam == null ? true : arParam;
        }

        // schedule a thread to finish initialization asynchronously since it can take several seconds
        scheduler.schedule(this::asyncInitialize, 0, TimeUnit.SECONDS);
    }

    private void asyncInitialize() {
        synchronized (asyncInitLock) {
            logger.debug("Async init thread staring for keypad handler {}", integrationId);

            buttonList.clear(); // in case we are re-initializing
            ledList.clear();
            cciList.clear();

            configureComponents(model);

            configureChannels();

            initDeviceState();

            logger.debug("Async init thread finishing for keypad handler {}", integrationId);
        }
    }

    @Override
    public void initDeviceState() {
        synchronized (asyncInitLock) {
            logger.debug("Initializing device state for Keypad {}", integrationId);
            Bridge bridge = getBridge();
            if (bridge == null) {
                logger.debug("Bridge unavailable for Thing: {}", thing.getUID());
            } else if (bridge.getStatus() == ThingStatus.ONLINE) {
                if (ledList.isEmpty()) {
                    // Device with no LEDs has nothing to query. Assume it is online if bridge is online.
                    updateStatus(ThingStatus.ONLINE);
                } else {
                    // Query LED states. Method handleUpdate() will set thing status to online when response arrives.
                    updateStatus(ThingStatus.UNKNOWN, "Awaiting initial response");
                    // To reduce query volume, query only 1st LED and LEDs with linked channels.
                    for (KeypadComponent component : ledList) {
                        if (component.id() == ledList.get(0).id() || isLinked(channelFromComponent(component.id()))) {
                            queryDevice(commandTargetType, component.id(), DeviceCommand.ACTION_LED_STATE);
                        }
                    }
                }
            } else {
                updateStatus(ThingStatusDetail.BRIDGE_OFFLINE);
            }
        }
    }

    @Override
    public void handleCommand(final ChannelUID channelUID, Command command) {
        logger.debug("Handling command {} for channel {}", command, channelUID);

        Channel channel = getThing().getChannel(channelUID.getId());
        if (channel == null) {
            logger.warn("Command received on invalid channel {} for device {}", channelUID, getThing().getUID());
            return;
        }

        Integer componentID = componentFromChannel(channelUID);
        if (componentID == null) {
            logger.warn("Command received on invalid channel {} for device {}", channelUID, getThing().getUID());
            return;
        }

        // For LEDs, handle RefreshType and OnOffType commands
        if (isLed(componentID)) {
            if (command instanceof RefreshType) {
                queryDevice(commandTargetType, componentID, DeviceCommand.ACTION_LED_STATE);
            } else if (command instanceof OnOffType) {
                if (command == OnOffType.ON) {
                    device(commandTargetType, componentID, null, DeviceCommand.ACTION_LED_STATE, DeviceCommand.LED_ON);
                } else if (command == OnOffType.OFF) {
                    device(commandTargetType, componentID, null, DeviceCommand.ACTION_LED_STATE, DeviceCommand.LED_OFF);
                }
            } else {
                logger.warn("Invalid command {} received for channel {} device {}", command, channelUID,
                        getThing().getUID());
            }
            return;
        }

        // For buttons, handle OnOffType commands. Trigger channels (physical keypads) do not accept commands.
        if (isButton(componentID)) {
            if (useTriggerChannels) {
                logger.debug("Commands not supported on trigger channel {} device {}", channelUID, getThing().getUID());
            } else if (command instanceof OnOffType) {
                // Annotate commands with LEAP button number for LEAP bridge
                Integer leapComponent = (this.leapButtonMap == null) ? null : leapButtonMap.get(componentID);
                if (command == OnOffType.ON) {
                    device(commandTargetType, componentID, leapComponent, DeviceCommand.ACTION_PRESS, null);
                    if (autoRelease) {
                        device(commandTargetType, componentID, leapComponent, DeviceCommand.ACTION_RELEASE, null);
                    }
                } else if (command == OnOffType.OFF) {
                    device(commandTargetType, componentID, leapComponent, DeviceCommand.ACTION_RELEASE, null);
                }
            } else {
                logger.warn("Invalid command type {} received for channel {} device {}", command, channelUID,
                        getThing().getUID());
            }
            return;
        }

        // Contact channels for CCIs are read-only, so ignore commands
        if (isCCI(componentID)) {
            logger.debug("Invalid command type {} received for channel {} device {}", command, channelUID,
                    getThing().getUID());
            return;
        }
    }

    @Override
    public void channelLinked(ChannelUID channelUID) {
        logger.debug("Linking keypad {} channel {}", integrationId, channelUID.getId());

        Integer id = componentFromChannel(channelUID);
        if (id == null) {
            logger.warn("Unrecognized channel ID {} linked", channelUID.getId());
            return;
        }

        // if this channel is for an LED, query the Lutron controller for the current state
        if (isLed(id)) {
            queryDevice(commandTargetType, id, DeviceCommand.ACTION_LED_STATE);
        }
        // Button and CCI state can't be queried, only monitored for updates.
        // Init button state to OFF on channel init. Trigger channels (physical keypads) have no state.
        if (isButton(id) && !useTriggerChannels) {
            updateState(channelUID, OnOffType.OFF);
        }
        // Leave CCI channel state undefined on channel init.
    }

    private static @Nullable ButtonEvent buttonEvent(String action) {
        if (DeviceCommand.ACTION_PRESS.toString().equals(action)) {
            return ButtonEvent.PRESS;
        } else if (DeviceCommand.ACTION_RELEASE.toString().equals(action)) {
            return ButtonEvent.RELEASE;
        } else if (DeviceCommand.ACTION_HOLD.toString().equals(action)) {
            return ButtonEvent.HOLD;
        } else if (DeviceCommand.ACTION_DBLTAP.toString().equals(action)) {
            return ButtonEvent.DOUBLE_TAP;
        } else if (DeviceCommand.ACTION_HOLDRELEASE.toString().equals(action)) {
            return ButtonEvent.HOLD_RELEASE;
        }
        return null;
    }

    @Override
    public void handleUpdate(LutronCommandType type, String... parameters) {
        logger.trace("Handling command {} {} from keypad {}", type, parameters, integrationId);
        if (type == LutronCommandType.DEVICE && parameters.length >= 2) {
            int component;

            try {
                component = Integer.parseInt(parameters[0]);
            } catch (NumberFormatException e) {
                logger.error("Invalid component {} in keypad update event message", parameters[0]);
                return;
            }

            // LEAP buttons need to be translated back from their index to component id
            if (leapButtonInverseMap != null) {
                component = leapButtonInverseMap.get(component);
            }

            ChannelUID channelUID = channelFromComponent(component);

            if (channelUID != null) {
                if (DeviceCommand.ACTION_LED_STATE.toString().equals(parameters[1]) && parameters.length >= 3) {
                    if (getThing().getStatus() == ThingStatus.UNKNOWN) {
                        updateStatus(ThingStatus.ONLINE); // set thing status online if this is an initial response
                    }
                    if (DeviceCommand.LED_ON.toString().equals(parameters[2])) {
                        updateState(channelUID, OnOffType.ON);
                    } else if (DeviceCommand.LED_OFF.toString().equals(parameters[2])) {
                        updateState(channelUID, OnOffType.OFF);
                    }
                } else {
                    ButtonEvent event = buttonEvent(parameters[1]);
                    if (event != null) {
                        channels.buttonEvent(component, event, Boolean.TRUE.equals(autoRelease));
                    }
                }
            } else {
                logger.warn("Unable to determine channel for component {} in keypad update event message",
                        parameters[0]);
            }
        }
    }
}
