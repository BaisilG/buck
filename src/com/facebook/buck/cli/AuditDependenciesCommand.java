/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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

package com.facebook.buck.cli;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.parser.ParserPythonInterpreterProvider;
import com.facebook.buck.parser.PerBuildState;
import com.facebook.buck.parser.PerBuildStateFactory;
import com.facebook.buck.parser.SpeculativeParsing;
import com.facebook.buck.rules.coercer.DefaultConstructorArgMarshaller;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.MoreExceptions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

public class AuditDependenciesCommand extends AbstractCommand {

  @Option(name = "--json", usage = "Output in JSON format")
  private boolean generateJsonOutput;

  public boolean shouldGenerateJsonOutput() {
    return generateJsonOutput;
  }

  @Option(
      name = "--include-tests",
      usage =
          "Includes a target's tests with its dependencies. With the transitive flag, this "
              + "prints the dependencies of the tests as well")
  private boolean includeTests = false;

  @Option(
      name = "--transitive",
      aliases = {"-t"},
      usage = "Whether to include transitive dependencies in the output")
  private boolean transitive = false;

  @Argument private List<String> arguments = new ArrayList<>();

  public List<String> getArguments() {
    return arguments;
  }

  public boolean shouldShowTransitiveDependencies() {
    return transitive;
  }

  public boolean shouldIncludeTests() {
    return includeTests;
  }

  public ImmutableList<String> getArgumentsFormattedAsBuildTargets(
      Cell rootCell, Path clientWorkingDir, BuckConfig buckConfig) {
    return getCommandLineBuildTargetNormalizer(rootCell, clientWorkingDir, buckConfig)
        .normalizeAll(getArguments());
  }

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params) throws Exception {
    ImmutableSet<String> fullyQualifiedBuildTargets =
        ImmutableSet.copyOf(
            getArgumentsFormattedAsBuildTargets(
                params.getCells().getRootCell(),
                params.getClientWorkingDir(),
                params.getBuckConfig()));

    if (fullyQualifiedBuildTargets.isEmpty()) {
      throw new CommandLineException("must specify at least one build target");
    }

    if (params.getConsole().getAnsi().isAnsiTerminal()) {
      params
          .getBuckEventBus()
          .post(
              ConsoleEvent.info(
                  "'buck audit dependencies' is deprecated. Please use 'buck query' instead.\n"
                      + "The equivalent 'buck query' command is:\n$ %s\n\nThe query language is documented at "
                      + "https://buck.build/command/query.html",
                  buildEquivalentQueryInvocation(
                      getArguments(),
                      shouldShowTransitiveDependencies(),
                      shouldIncludeTests(),
                      shouldGenerateJsonOutput())));
    }

    try (CommandThreadManager pool =
            new CommandThreadManager("Audit", getConcurrencyLimit(params.getBuckConfig()));
        PerBuildState parserState =
            new PerBuildStateFactory(
                    params.getTypeCoercerFactory(),
                    new DefaultConstructorArgMarshaller(),
                    params.getKnownRuleTypesProvider(),
                    new ParserPythonInterpreterProvider(
                        params.getCells().getRootCell().getBuckConfig(),
                        params.getExecutableFinder()),
                    params.getWatchman(),
                    params.getBuckEventBus(),
                    params.getUnconfiguredBuildTargetFactory(),
                    params.getHostConfiguration().orElse(UnconfiguredTargetConfiguration.INSTANCE))
                .create(
                    createParsingContext(
                            params.getCells().getRootCell(), pool.getListeningExecutorService())
                        .withSpeculativeParsing(SpeculativeParsing.ENABLED)
                        .withExcludeUnsupportedTargets(false),
                    params.getParser().getPermState())) {
      BuckQueryEnvironment env =
          BuckQueryEnvironment.from(
              params,
              parserState,
              createParsingContext(
                  params.getCells().getRootCell(), pool.getListeningExecutorService()));
      QueryCommand.runMultipleQuery(
          params,
          env,
          getQueryFormat(shouldShowTransitiveDependencies(), shouldIncludeTests()),
          getArgumentsFormattedAsBuildTargets(
              params.getCells().getRootCell(),
              params.getClientWorkingDir(),
              params.getBuckConfig()),
          shouldGenerateJsonOutput(),
          ImmutableSet.of(),
          params.getConsole().getStdOut());
    } catch (Exception e) {
      if (e.getCause() instanceof InterruptedException) {
        throw (InterruptedException) e.getCause();
      }
      params
          .getBuckEventBus()
          .post(ConsoleEvent.severe(MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
      // TODO(buck_team): catch specific exceptions and return proper codes
      return ExitCode.BUILD_ERROR;
    }
    return ExitCode.SUCCESS;
  }

  private static String getQueryFormat(boolean isTransitive, boolean includeTests) {
    StringBuilder queryBuilder = new StringBuilder();
    queryBuilder.append(isTransitive ? "deps('%s') " : "deps('%s', 1) ");
    if (includeTests) {
      queryBuilder.append(isTransitive ? "union deps(testsof(deps('%s')))" : "union testsof('%s')");
    }
    queryBuilder.append(" except set('%s')");
    return queryBuilder.toString();
  }

  /** @return The 'buck query' invocation that's equivalent to 'buck audit dependencies'. */
  private static String buildEquivalentQueryInvocation(
      List<String> arguments, boolean isTransitive, boolean includeTests, boolean jsonOutput) {
    StringBuilder queryBuilder = new StringBuilder("buck query ");
    queryBuilder.append("\"").append(getQueryFormat(isTransitive, includeTests)).append("\" ");
    queryBuilder.append(AbstractQueryCommand.getEscapedArgumentsListAsString(arguments));
    if (jsonOutput) {
      queryBuilder.append(AbstractQueryCommand.getJsonOutputParamDeclaration());
    }
    return queryBuilder.toString();
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  @Override
  public String getShortDescription() {
    return "provides facilities to audit build targets' dependencies";
  }
}
