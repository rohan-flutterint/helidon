/*
 * Copyright (c) 2019, 2025 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.helidon.common.features.api;

import io.helidon.common.features.metadata.Flavor;

/**
 * Flavors of Helidon.
 */
public enum HelidonFlavor {
    /**
     * The "Standard Edition" flavor.
     */
    SE,
    /**
     * The "MicroProfile" flavor.
     */
    MP;

    /**
     * Map from metadata flavor.
     *
     * @param flavor metadata flavor (as loaded from JSON descriptor).
     * @return Helidon flavor that matches the metadata flavor (this is a simple copy)
     */
    public static HelidonFlavor map(Flavor flavor) {
        return switch (flavor) {
            case SE -> SE;
            case MP -> MP;
        };
    }
}
