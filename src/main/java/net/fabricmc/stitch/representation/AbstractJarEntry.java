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

import java.nio.charset.StandardCharsets;

import net.fabricmc.stitch.Main;

public abstract class AbstractJarEntry
{
    byte[] hash = new byte[0];
    protected String name;
    protected String parentName;
    protected int access;

    public AbstractJarEntry(String name, String parentName) {
        this.name = name;
        this.parentName = parentName;
    }

    public int getAccess() {
        return access;
    }

    protected void setAccess(int value) {
        this.access = value;
    }

    public String getName() {
        return name;
    }

    public String getParentName() {
        return parentName;
    }

    protected String getKey() {
        return name;
    }

    protected void hash(byte[] parentHash) {
        Main.MESSAGE_DIGEST.reset();
        Main.MESSAGE_DIGEST.update(parentHash);
        Main.MESSAGE_DIGEST.update(getKey().getBytes(StandardCharsets.UTF_8));
        hash = Main.MESSAGE_DIGEST.digest();
    }

    public byte[] getHash() {
        return hash;
    }

    @Override
    public boolean equals(Object other) {
        return other != null && other.getClass() == getClass() && ((AbstractJarEntry) other).parentName.equals(parentName) && ((AbstractJarEntry) other).getKey().equals(getKey());
    }

    @Override
    public int hashCode() {
        return (parentName + getKey()).hashCode();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(" + getKey() + ")";
    }
}
