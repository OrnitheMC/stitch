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

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

public class JarFieldEntry extends AbstractJarEntry
{
    final byte[] saltedFieldHash;
    protected String desc;
    protected String signature;

    JarFieldEntry(int access, String name, String desc, String signature, JarClassEntry parentClass) {
        super(name, parentClass.fullyQualifiedName);
        this.setAccess(access);
        this.desc = desc;
        this.signature = signature;

        Main.MESSAGE_DIGEST.update(parentClass.getHash());
        Main.MESSAGE_DIGEST.update(BigInteger.valueOf(access).toByteArray());
        Main.MESSAGE_DIGEST.update(getKey().getBytes(StandardCharsets.UTF_8));
        this.saltedFieldHash = Main.MESSAGE_DIGEST.digest();
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
}
