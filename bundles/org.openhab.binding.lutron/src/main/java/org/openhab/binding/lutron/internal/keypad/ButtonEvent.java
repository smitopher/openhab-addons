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

import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.CommonTriggerEvents;

/**
 * A button action reported by a keypad, independent of the Lutron protocol that reported it.
 *
 * @author Christopher Smith - Initial contribution
 */
@NonNullByDefault
public enum ButtonEvent {
    PRESS,
    RELEASE,
    HOLD,
    DOUBLE_TAP,
    /** The end of a hold, which some systems report instead of a release. */
    HOLD_RELEASE;

    /**
     * Returns the events to fire on a trigger channel for this action.
     *
     * @param releaseAfterHold whether a hold is followed by a release event, for systems that report no release after
     *            a hold
     */
    public List<String> triggerEvents(boolean releaseAfterHold) {
        return switch (this) {
            case PRESS -> List.of(CommonTriggerEvents.PRESSED);
            case RELEASE, HOLD_RELEASE -> List.of(CommonTriggerEvents.RELEASED);
            case HOLD -> releaseAfterHold ? List.of(CommonTriggerEvents.LONG_PRESSED, CommonTriggerEvents.RELEASED)
                    : List.of(CommonTriggerEvents.LONG_PRESSED);
            case DOUBLE_TAP -> List.of(CommonTriggerEvents.DOUBLE_PRESSED);
        };
    }
}
