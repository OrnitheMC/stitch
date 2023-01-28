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

public class CommandUpdateCalamus extends Command {
    public CommandUpdateCalamus() {
        super("updateCalamus");
    }

    @Override
    public String getHelpString() {
        return "<old-jars>... <new-jar> <old-mapping-files>... <new-mapping-file> <match-files>... [-t|--target-namespace <namespace>] [-p|--obfuscation-pattern <regex pattern>] [--client-hash <hash>] [--server-hash <hash>]";
    }

    @Override
    public boolean isArgumentCountValid(int count) {
        return count >= 5;
    }

    @Override
    public void run(String[] args) throws Exception {
        CalamusUtil.updateCalamus(CalamusUtil.parseArgs(args));
    }
}
