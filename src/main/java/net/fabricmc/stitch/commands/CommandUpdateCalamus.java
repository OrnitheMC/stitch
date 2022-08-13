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

package net.fabricmc.stitch.commands;

import net.fabricmc.stitch.Command;
import net.fabricmc.stitch.util.CalamusUtil;

import java.io.File;

public class CommandUpdateCalamus extends Command {
    public CommandUpdateCalamus() {
        super("updateCalamus");
    }

    @Override
    public String getHelpString() {
        return "<old-jar> <new-jar> <old-mapping-file> <new-mapping-file> <match-file> [-t|--target-namespace <namespace>] [-p|--obfuscation-pattern <regex pattern>]";
    }

    @Override
    public boolean isArgumentCountValid(int count) {
        return count >= 5;
    }

    @Override
    public void run(String[] args) throws Exception {
        File oldJarFile = new File(args[0]);
        File newJarFile = new File(args[1]);
        File oldCalamusFile = new File(args[2]);
        File newCalamusFile = new File(args[3]);
        File matchesFile = new File(args[4]);
        CalamusUtil.updateCalamus(oldJarFile, newJarFile, oldCalamusFile, newCalamusFile, matchesFile, args);
    }
}
