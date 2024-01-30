/*
 * Copyright (c) 2024 FabricMC
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

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import net.fabricmc.stitch.Command;
import net.fabricmc.stitch.representation.JarClassEntry;
import net.fabricmc.stitch.representation.JarReader;
import net.fabricmc.stitch.representation.JarRootEntry;

public class CommandGenerateNests extends Command {

    public CommandGenerateNests() {
        super("generateNests");
    }

    @Override
    public String getHelpString() {
        return "<input-jar> <output-nests>";
    }

    @Override
    public boolean isArgumentCountValid(int count) {
        return count == 2;
    }

    @Override
    public void run(String[] args) throws Exception {
        Path input = Paths.get(args[0]);
        Path output = Paths.get(args[1]);

        if (input.equals(output)) {
            throw new RuntimeException("input and output paths cannot be the same!");
        }
        if (!Files.exists(input) || !Files.isReadable(input)) {
            throw new RuntimeException("cannot read from input path");
        }
        if (Files.exists(output) && !Files.isWritable(output)) {
            throw new RuntimeException("cannot write to output path");
        }

        JarRootEntry jar = new JarRootEntry(input.toFile());
        try {
            new JarReader(jar).apply();
        } catch (IOException e) {
            throw new RuntimeException("could not read input jar", e);
        }
        try (BufferedWriter bw = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            for (JarClassEntry cls : jar.getAllClasses()) {
                if (cls.isAnonymous()) {
                    String fullName = cls.getName();
                    int i = fullName.lastIndexOf('$');

                    if (i < fullName.length()) {
                        bw.write(fullName);
                        bw.write("\t");
                        bw.write(cls.getEnclosingClassName());
                        bw.write("\t");
                        bw.write(cls.hasEnclosingMethod() ? cls.getEnclosingMethodName() : "");
                        bw.write("\t");
                        bw.write(cls.hasEnclosingMethod() ? cls.getEnclosingMethodDescriptor() : "");
                        bw.write("\t");
                        bw.write(fullName.substring(i + 1));
                        bw.write("\t");
                        bw.write(Integer.toString(cls.getInnerAccess()));
                        bw.newLine();
                    } else {
                        System.err.println("don't know how to handle anonymous class " + fullName);
                    }
                }
                if (cls.isInner()) {
                    String fullName = cls.getName();
                    String innerName = cls.getInnerName();

                    if (fullName.endsWith("$" + innerName)) {
                        bw.write(fullName);
                        bw.write("\t");
                        bw.write(cls.getDeclaringClassName());
                        bw.write("\t");
                        bw.write("");
                        bw.write("\t");
                        bw.write("");
                        bw.write("\t");
                        bw.write(innerName);
                        bw.write("\t");
                        bw.write(Integer.toString(cls.getInnerAccess()));
                        bw.newLine();
                    } else {
                        System.err.println("don't know how to handle inner class " + fullName + "/" + innerName);
                    }
                }
                if (cls.isLocal()) {
                    String fullName = cls.getName();
                    String innerName = cls.getInnerName();
                    int i = fullName.lastIndexOf('$');

                    if (fullName.endsWith(innerName) && i <= (fullName.length() - innerName.length())) {
                        bw.write(fullName);
                        bw.write("\t");
                        bw.write(cls.getDeclaringClassName());
                        bw.write("\t");
                        bw.write(cls.hasEnclosingMethod() ? cls.getEnclosingMethodName() : "");
                        bw.write("\t");
                        bw.write(cls.hasEnclosingMethod() ? cls.getEnclosingMethodDescriptor() : "");
                        bw.write("\t");
                        bw.write(fullName.substring(i + 1, fullName.length() - innerName.length()) + innerName);
                        bw.write("\t");
                        bw.write(Integer.toString(cls.getInnerAccess()));
                        bw.newLine();
                    } else {
                        System.err.println("don't know how to handle local class " + fullName + "/" + innerName);
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("could not write output nests", e);
        }
    }
}
