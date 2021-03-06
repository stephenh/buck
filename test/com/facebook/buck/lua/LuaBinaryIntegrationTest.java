/*
 * Copyright 2015-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.lua;

import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.config.Config;
import com.facebook.buck.config.Configs;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.DefaultCxxPlatforms;
import com.facebook.buck.cxx.NativeLinkStrategy;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.FakeExecutableFinder;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultCellPathResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.testutil.ParameterizedTests;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.Platform;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;

@RunWith(Parameterized.class)
public class LuaBinaryIntegrationTest {

  private ProjectWorkspace workspace;
  private Path lua;
  private boolean luaDevel;

  @Parameterized.Parameters(name = "{0} {1}")
  public static Collection<Object[]> data() {
    return ParameterizedTests.getPermutations(
        Arrays.asList(LuaBinaryDescription.StarterType.values()),
        Arrays.asList(NativeLinkStrategy.values()));
  }

  @Parameterized.Parameter
  public LuaBinaryDescription.StarterType starterType;

  @Parameterized.Parameter(value = 1)
  public NativeLinkStrategy nativeLinkStrategy;

  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  @Before
  public void setUp() throws Exception {

    // We don't currently support windows.
    assumeThat(Platform.detect(), Matchers.not(Platform.WINDOWS));

    // Verify that a Lua interpreter is available on the system.
    LuaBuckConfig fakeConfig =
        new LuaBuckConfig(
            FakeBuckConfig.builder().build(),
            new ExecutableFinder());
    Optional<Path> luaOptional = fakeConfig.getSystemLua();
    assumeTrue(luaOptional.isPresent());
    lua = luaOptional.get();

    // Try to detect if a Lua devel package is available, which is needed to C/C++ support.
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraph.EMPTY,
            new DefaultTargetNodeToBuildRuleTransformer());
    CxxPlatform cxxPlatform =
        DefaultCxxPlatforms.build(
            Platform.detect(),
            new CxxBuckConfig(FakeBuckConfig.builder().build()));
    ProcessExecutorParams params = ProcessExecutorParams.builder()
        .setCommand(
        ImmutableList.<String>builder()
            .addAll(
                cxxPlatform.getCc().resolve(resolver)
                    .getCommandPrefix(new SourcePathResolver(resolver)))
            .add("-includelua.h", "-E", "-")
            .build())
        .setRedirectInput(ProcessBuilder.Redirect.PIPE)
        .build();
    ProcessExecutor executor = new ProcessExecutor(Console.createNullConsole());
    ProcessExecutor.LaunchedProcess launchedProcess = executor.launchProcess(params);
    launchedProcess.getOutputStream().close();
    int exitCode = executor.waitForLaunchedProcess(launchedProcess).getExitCode();
    luaDevel = exitCode == 0;
    if (starterType == LuaBinaryDescription.StarterType.NATIVE) {
      assumeTrue("Lua devel package required for native starter", luaDevel);
    }

    // Setup the workspace.
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "lua_binary", tmp);
    workspace.setUp();
    workspace.writeContentsToPath(
        Joiner.on(System.lineSeparator()).join(
            ImmutableList.of(
                "[lua]",
                "  starter_type = " + starterType.toString().toLowerCase(),
                "  native_link_strategy = " + nativeLinkStrategy.toString().toLowerCase())),
        ".buckconfig");
    LuaBuckConfig config = getLuaBuckConfig();
    assertThat(config.getStarterType(), Matchers.equalTo(Optional.of(starterType)));
    assertThat(config.getNativeLinkStrategy(), Matchers.equalTo(nativeLinkStrategy));
  }

  @Test
  public void stdout() throws Exception {
    workspace.writeContentsToPath("require 'os'; io.stdout:write('hello world')", "simple.lua");
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("run", "//:simple").assertSuccess();
    assertThat(
        result.getStdout() + result.getStderr(),
        result.getStdout().trim(),
        Matchers.equalTo("hello world"));
  }

  @Test
  public void stderr() throws Exception {
    workspace.writeContentsToPath("require 'os'; io.stderr:write('hello world')", "simple.lua");
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("run", "//:simple").assertSuccess();
    assertThat(
        result.getStdout() + result.getStderr(),
        result.getStderr().trim(),
        Matchers.endsWith("hello world"));
  }

  @Test
  public void errorCode() throws Exception {
    workspace.writeContentsToPath("require 'os'\nos.exit(5)", "simple.lua");
    workspace.runBuckBuild("//:simple").assertSuccess();
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("run", "//:simple");
    assertThat(result.getExitCode(), Matchers.equalTo(5));
  }

  @Test
  public void error() throws Exception {
    workspace.writeContentsToPath("blah blah garbage", "simple.lua");
    workspace.runBuckBuild("//:simple").assertSuccess();
    workspace.runBuckCommand("run", "//:simple").assertFailure();
  }

  @Test
  public void args() throws Exception {
    workspace.writeContentsToPath("for i=-1,#arg do print(arg[i]) end", "simple.lua");
    Path arg0 = workspace.buildAndReturnOutput("//:simple");

    // no args...
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("run", "//:simple").assertSuccess();
    assertThat(
        result.getStdout() + result.getStderr(),
        Splitter.on(System.lineSeparator())
            .splitToList(result.getStdout().trim()),
        Matchers.contains(
            ImmutableList.<Matcher<? super String>>of(
                Matchers.anyOf(Matchers.equalTo(lua.toString()), Matchers.equalTo("nil")),
                Matchers.endsWith(arg0.toString()))));

    // with args...
    result = workspace.runBuckCommand("run", "//:simple", "--", "hello", "world").assertSuccess();
    assertThat(
        result.getStdout() + result.getStderr(),
        Splitter.on(System.lineSeparator())
            .splitToList(result.getStdout().trim()),
        Matchers.contains(
            ImmutableList.<Matcher<? super String>>of(
                Matchers.anyOf(Matchers.equalTo(lua.toString()), Matchers.equalTo("nil")),
                Matchers.endsWith(arg0.toString()),
                Matchers.equalTo("hello"),
                Matchers.equalTo("world"))));
  }

  @Test
  public void nativeExtension() throws Exception {
    assumeTrue(luaDevel);
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("run", "//:native").assertSuccess();
    assertThat(
        result.getStdout() + result.getStderr(),
        result.getStdout().trim(),
        Matchers.equalTo("hello world"));
  }

  @Test
  public void nativeExtensionWithDep() throws Exception {
    assumeThat(starterType, Matchers.not(Matchers.equalTo(LuaBinaryDescription.StarterType.PURE)));
    assumeTrue(luaDevel);
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("run", "//:native_with_dep").assertSuccess();
    assertThat(
        result.getStdout() + result.getStderr(),
        result.getStdout().trim(),
        Matchers.equalTo("hello world"));
  }

  @Test
  public void packagedFormat() throws Exception {
    Path output =
        workspace.buildAndReturnOutput(
            "-c", "lua.package_style=standalone",
            "-c", "lua.packager=//:packager",
            "//:simple");
    ObjectMapper mapper = ObjectMappers.newDefaultInstance();
    ImmutableMap<String, ImmutableMap<String, String>> components =
        mapper.readValue(
            output.toFile(),
            new TypeReference<ImmutableMap<String, ImmutableMap<String, String>>>() {});
    assertThat(
        components.get("modules").keySet(),
        Matchers.equalTo(ImmutableSet.of("simple.lua")));
  }

  @Test
  @SuppressWarnings("PMD.UseAssertEqualsInsteadOfAssertTrue")
  public void switchingBetweenPacakgedFormats() throws Exception {

    // Run an inital build using the standalone packaging style.
    String standaloneFirst =
        workspace.getFileContents(
            workspace.buildAndReturnOutput(
                "-c", "lua.package_style=standalone",
                "-c", "lua.packager=//:packager",
                "//:simple"));

    // Now rebuild with just changing to an in-place packaging style.
    String inplaceFirst =
        workspace.getFileContents(
            workspace.buildAndReturnOutput(
                "-c", "lua.package_style=inplace",
                "//:simple"));

    // Now rebuild again, switching back to standalone, and verify the output matches the original
    // build's output.
    String standaloneSecond =
        workspace.getFileContents(
            workspace.buildAndReturnOutput(
                "-c", "lua.package_style=standalone",
                "-c", "lua.packager=//:packager",
                "//:simple"));
    assertTrue(standaloneFirst.equals(standaloneSecond));

    // Now rebuild again, switching back to in-place, and verify the output matches the original
    // build's output.
    String inplaceSecond =
        workspace.getFileContents(
            workspace.buildAndReturnOutput(
                "-c", "lua.package_style=inplace",
                "//:simple"));
    assertTrue(inplaceFirst.equals(inplaceSecond));
  }

  private LuaBuckConfig getLuaBuckConfig() throws IOException {
    Config rawConfig = Configs.createDefaultConfig(tmp.getRoot());
    BuckConfig buckConfig =
        new BuckConfig(
            rawConfig,
            new ProjectFilesystem(tmp.getRoot()),
            Architecture.detect(),
            Platform.detect(),
            ImmutableMap.<String, String>of(),
            new DefaultCellPathResolver(tmp.getRoot(), rawConfig));
    return new LuaBuckConfig(
        buckConfig,
        new FakeExecutableFinder(ImmutableList.<Path>of()));
  }

}
