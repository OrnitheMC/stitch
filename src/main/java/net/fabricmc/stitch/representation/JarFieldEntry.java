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

import net.fabricmc.stitch.Main;
import org.apache.commons.lang3.ArrayUtils;
import org.objectweb.asm.commons.Remapper;

import java.nio.ByteBuffer;

public class JarFieldEntry extends AbstractJarEntry
{
    final byte[] saltedFieldHash;
    protected String desc;
    protected String signature;

    JarFieldEntry(int access, String name, String desc, String signature, JarClassEntry parentClass) {
        super(name);
        this.setAccess(access);
        this.desc = desc;
        this.signature = signature;

        Main.MESSAGE_DIGEST.update(parentClass.getHash());
        byte[] bytes = ArrayUtils.addAll(ByteBuffer.allocate(4).putInt(getAccess()).array(), getKey().getBytes());
        this.saltedFieldHash = Main.MESSAGE_DIGEST.digest(bytes);
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
    public byte[] getHash() {
        return saltedFieldHash;
    }

    public void remap(JarClassEntry classEntry, String oldOwner, Remapper remapper) {
        String pastDesc = desc;

        name = remapper.mapFieldName(oldOwner, name, pastDesc);
        desc = remapper.mapDesc(pastDesc);
    }
}
