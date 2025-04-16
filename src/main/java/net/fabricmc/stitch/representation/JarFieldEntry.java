/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
 * Modifications copyright (c) 2022 OrnitheMC
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

package net.fabricmc.stitch.representation;

public class JarFieldEntry extends AbstractJarEntry
{
    protected String desc;
    protected String signature;

    JarFieldEntry(int access, String name, String desc, String signature, String parentName) {
        super(name, parentName);
        this.setAccess(access);
        this.desc = desc;
        this.signature = signature;
    }

    public String getDescriptor() {
        return desc;
    }

    public String getSignature() {
        return signature;
    }

    @Override
    protected String getKey() {
        return super.getKey() + desc;
    }

    @Override
    public char getPrefix() {
        return 'f';
    }

    @Override
    public boolean isSerializable(Classpath storage) {
        if (Access.isPrivate(access) && (Access.isStatic(access) || Access.isTransient(access))) {
            // these fields are specific to Serializable classes, but are static
            if (!"serialVersionUID".equals(name) && !"serialPersistentFields".equals(name)) {
                return false;
            }
        }

        JarClassEntry parent = storage.getClass(parentName);
        return parent != null && parent.isSerializable(storage);
    }

    @Override
    public boolean isMainJar(Classpath storage) {
        return storage.getClass(parentName).isMainJar(storage);
    }
}
