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
import net.fabricmc.stitch.representation.*;
import net.fabricmc.stitch.util.CalamusUtil;

import java.io.*;
import java.util.Locale;

public class CommandGenerateCalamus extends Command {
    public CommandGenerateCalamus() {
        super("generateCalamus");
    }

    @Override
    public String getHelpString() {
        return "<input-jar> <mapping-name> [-t|--target-namespace <namespace>] [-p|--obfuscation-pattern <regex pattern>]...";
    }

    @Override
    public boolean isArgumentCountValid(int count) {
        return count >= 2;
    }

    @Override
    public void run(String[] args) throws Exception {
        File jarFile = new File(args[0]);
        File calamusFile = new File(args[1]);
        CalamusUtil.generateCalamus(jarFile, calamusFile, args);
    }
}