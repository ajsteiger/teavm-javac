/*
 *  Copyright 2026 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.teavm.visualizer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import org.teavm.classlib.ReflectionContext;
import org.teavm.classlib.ReflectionSupplier;
import org.teavm.model.ClassReader;
import org.teavm.model.ElementModifier;
import org.teavm.model.FieldReader;

/**
 * Makes user-code instance fields available to runtime reflection for the Java
 * visualizer heap panel.
 *
 * <p>The supplier is registered in {@code core} so browser-hosted compilers can
 * bundle it, but it returns no fields until visualizer reflection is enabled
 * for a visualizer build.</p>
 */
public class AllFieldsReflectionSupplier implements ReflectionSupplier {
    @Override
    public Collection<String> getAccessibleFields(ReflectionContext context, String className) {
        if (!StepInstrumentationTransformer.isVisualizerReflectionEnabled() || isSystemClass(className)) {
            return Collections.emptyList();
        }
        ClassReader cls = context.getClassSource().get(className);
        if (cls == null) {
            return Collections.emptyList();
        }
        Collection<String> fields = new ArrayList<>();
        for (FieldReader field : cls.getFields()) {
            if (!field.hasModifier(ElementModifier.STATIC)) {
                fields.add(field.getName());
            }
        }
        return fields;
    }

    private static boolean isSystemClass(String name) {
        return name.startsWith("java.") || name.startsWith("java/")
                || name.startsWith("javax.") || name.startsWith("javax/")
                || name.startsWith("sun.") || name.startsWith("sun/")
                || name.startsWith("org.teavm.") || name.startsWith("org/teavm/");
    }
}
