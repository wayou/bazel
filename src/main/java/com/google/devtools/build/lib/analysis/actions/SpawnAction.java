// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.analysis.actions;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CharMatcher;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionEnvironment;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputHelper;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.ActionResult;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.ArtifactExpander;
import com.google.devtools.build.lib.actions.BaseSpawn;
import com.google.devtools.build.lib.actions.CommandAction;
import com.google.devtools.build.lib.actions.CommandLine;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.actions.CommandLines;
import com.google.devtools.build.lib.actions.CommandLines.CommandLineAndParamFileInfo;
import com.google.devtools.build.lib.actions.CommandLines.CommandLineLimits;
import com.google.devtools.build.lib.actions.CommandLines.ExpandedCommandLines;
import com.google.devtools.build.lib.actions.CompositeRunfilesSupplier;
import com.google.devtools.build.lib.actions.EmptyRunfilesSupplier;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ExecutionInfoSpecifier;
import com.google.devtools.build.lib.actions.FilesetOutputSymlink;
import com.google.devtools.build.lib.actions.ParamFileInfo;
import com.google.devtools.build.lib.actions.ParameterFile;
import com.google.devtools.build.lib.actions.ResourceSet;
import com.google.devtools.build.lib.actions.RunfilesSupplier;
import com.google.devtools.build.lib.actions.SingleStringArgFormatter;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnActionContext;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.extra.EnvironmentVariable;
import com.google.devtools.build.lib.actions.extra.ExtraActionInfo;
import com.google.devtools.build.lib.actions.extra.SpawnInfo;
import com.google.devtools.build.lib.analysis.AnalysisEnvironment;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec.VisibleForSerialization;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.SkylarkList;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.util.LazyString;
import com.google.devtools.build.lib.util.ShellEscaper;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.errorprone.annotations.CompileTimeConstant;
import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;

/** An Action representing an arbitrary subprocess to be forked and exec'd. */
@AutoCodec
public class SpawnAction extends AbstractAction implements ExecutionInfoSpecifier, CommandAction {

  /** Sets extensions on {@link ExtraActionInfo}. */
  public interface ExtraActionInfoSupplier {
    void extend(ExtraActionInfo.Builder builder);
  }

  private static final String GUID = "ebd6fce3-093e-45ee-adb6-bf513b602f0d";

  @VisibleForSerialization protected final CommandLines commandLines;
  @VisibleForSerialization protected final CommandLineLimits commandLineLimits;

  private final boolean executeUnconditionally;
  private final boolean isShellCommand;
  private final CharSequence progressMessage;
  private final String mnemonic;

  private final ResourceSet resourceSet;
  private final ImmutableMap<String, String> executionInfo;

  private final ExtraActionInfoSupplier extraActionInfoSupplier;
  private final Artifact primaryOutput;

  /**
   * Constructs a SpawnAction using direct initialization arguments.
   *
   * <p>All collections provided must not be subsequently modified.
   *
   * @param owner the owner of the Action.
   * @param tools the set of files comprising the tool that does the work (e.g. compiler).
   * @param inputs the set of all files potentially read by this action; must not be subsequently
   *     modified.
   * @param outputs the set of all files written by this action; must not be subsequently modified.
   * @param primaryOutput the primary output of this action
   * @param resourceSet the resources consumed by executing this Action
   * @param env the action environment
   * @param commandLines the command lines to execute. This includes the main argv vector and any
   *     param file-backed command lines.
   * @param commandLineLimits the command line limits, from the build configuration
   * @param isShellCommand Whether the command line represents a shell command with the given shell
   *     executable. This is used to give better error messages.
   * @param progressMessage the message printed during the progression of the build.
   * @param mnemonic the mnemonic that is reported in the master log.
   */
  @AutoCodec.Instantiator
  public SpawnAction(
      ActionOwner owner,
      Iterable<Artifact> tools,
      Iterable<Artifact> inputs,
      Iterable<Artifact> outputs,
      Artifact primaryOutput,
      ResourceSet resourceSet,
      CommandLines commandLines,
      CommandLineLimits commandLineLimits,
      boolean isShellCommand,
      ActionEnvironment env,
      CharSequence progressMessage,
      String mnemonic) {
    this(
        owner,
        tools,
        inputs,
        outputs,
        primaryOutput,
        resourceSet,
        commandLines,
        commandLineLimits,
        isShellCommand,
        env,
        ImmutableMap.<String, String>of(),
        progressMessage,
        EmptyRunfilesSupplier.INSTANCE,
        mnemonic,
        false,
        null);
  }

  /**
   * Constructs a SpawnAction using direct initialization arguments.
   *
   * <p>All collections provided must not be subsequently modified.
   *
   * @param owner the owner of the Action
   * @param tools the set of files comprising the tool that does the work (e.g. compiler). This is a
   *     subset of "inputs" and is only used by the WorkerSpawnStrategy
   * @param inputs the set of all files potentially read by this action; must not be subsequently
   *     modified
   * @param outputs the set of all files written by this action; must not be subsequently modified.
   * @param primaryOutput the primary output of this action
   * @param resourceSet the resources consumed by executing this Action
   * @param env the action's environment
   * @param executionInfo out-of-band information for scheduling the spawn
   * @param commandLines the command lines to execute. This includes the main argv vector and any
   *     param file-backed command lines.
   * @param commandLineLimits the command line limits, from the build configuration
   * @param isShellCommand Whether the command line represents a shell command with the given shell
   *     executable. This is used to give better error messages.
   * @param progressMessage the message printed during the progression of the build
   * @param runfilesSupplier {@link RunfilesSupplier}s describing the runfiles for the action
   * @param mnemonic the mnemonic that is reported in the master log
   */
  public SpawnAction(
      ActionOwner owner,
      Iterable<Artifact> tools,
      Iterable<Artifact> inputs,
      Iterable<Artifact> outputs,
      Artifact primaryOutput,
      ResourceSet resourceSet,
      CommandLines commandLines,
      CommandLineLimits commandLineLimits,
      boolean isShellCommand,
      ActionEnvironment env,
      ImmutableMap<String, String> executionInfo,
      CharSequence progressMessage,
      RunfilesSupplier runfilesSupplier,
      String mnemonic,
      boolean executeUnconditionally,
      ExtraActionInfoSupplier extraActionInfoSupplier) {
    super(owner, tools, inputs, runfilesSupplier, outputs, env);
    this.primaryOutput = primaryOutput;
    this.resourceSet = resourceSet;
    this.executionInfo = executionInfo;
    this.commandLines = commandLines;
    this.commandLineLimits = commandLineLimits;
    this.isShellCommand = isShellCommand;
    this.progressMessage = progressMessage;
    this.mnemonic = mnemonic;
    this.executeUnconditionally = executeUnconditionally;
    this.extraActionInfoSupplier = extraActionInfoSupplier;
  }

  @Override
  public Artifact getPrimaryOutput() {
    return primaryOutput;
  }

  @VisibleForTesting
  public CommandLines getCommandLines() {
    return commandLines;
  }

  @Override
  @VisibleForTesting
  public List<String> getArguments() throws CommandLineExpansionException {
    return ImmutableList.copyOf(commandLines.allArguments());
  }

  @Override
  public SkylarkList<String> getSkylarkArgv() throws EvalException {
    try {
      return SkylarkList.createImmutable(getArguments());
    } catch (CommandLineExpansionException exception) {
      throw new EvalException(Location.BUILTIN, exception);
    }
  }

  @Override
  @VisibleForTesting
  public Iterable<Artifact> getPossibleInputsForTesting() {
    return getInputs();
  }

  /** Returns command argument, argv[0]. */
  @VisibleForTesting
  public String getCommandFilename() throws CommandLineExpansionException {
    return Iterables.getFirst(getArguments(), null);
  }

  /** Returns the (immutable) list of arguments, excluding the command name, argv[0]. */
  @VisibleForTesting
  public List<String> getRemainingArguments() throws CommandLineExpansionException {
    return ImmutableList.copyOf(Iterables.skip(getArguments(), 1));
  }

  @VisibleForTesting
  public boolean isShellCommand() {
    return isShellCommand;
  }

  @Override
  public boolean isVolatile() {
    return executeUnconditionally;
  }

  @Override
  public boolean executeUnconditionally() {
    return executeUnconditionally;
  }

  /**
   * Executes the action without handling ExecException errors.
   *
   * <p>Called by {@link #execute}.
   */
  protected List<SpawnResult> internalExecute(ActionExecutionContext actionExecutionContext)
      throws ExecException, InterruptedException, CommandLineExpansionException {
    Spawn spawn = getSpawn(actionExecutionContext);
    return actionExecutionContext.getContext(SpawnActionContext.class)
        .exec(spawn, actionExecutionContext);
  }

  @Override
  public ActionResult execute(ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException, InterruptedException {
    try {
      return ActionResult.create(internalExecute(actionExecutionContext));
    } catch (ExecException e) {
      String failMessage;
      if (isShellCommand()) {
        // The possible reasons it could fail are: shell executable not found, shell
        // exited non-zero, or shell died from signal.  The first is impossible
        // and the second two aren't very interesting, so in the interests of
        // keeping the noise-level down, we don't print a reason why, just the
        // command that failed.
        //
        // 0=shell executable, 1=shell command switch, 2=command
        try {
          failMessage =
              "error executing shell command: "
                  + "'"
                  + truncate(Iterables.get(getArguments(), 2), 200)
                  + "'";
        } catch (CommandLineExpansionException commandLineExpansionException) {
          failMessage =
              "error executing shell command, and error expanding command line: "
                  + commandLineExpansionException;
        }
      } else {
        failMessage = getRawProgressMessage();
      }
      throw e.toActionExecutionException(
          failMessage, actionExecutionContext.getVerboseFailures(), this);
    } catch (CommandLineExpansionException e) {
      throw new ActionExecutionException(e, this, false);
    }
  }

  /**
   * Returns s, truncated to no more than maxLen characters, appending an
   * ellipsis if truncation occurred.
   */
  private static String truncate(String s, int maxLen) {
    return s.length() > maxLen
        ? s.substring(0, maxLen - "...".length()) + "..."
        : s;
  }

  /**
   * Returns a Spawn that is representative of the command that this Action will execute. This
   * function must not modify any state.
   *
   * <p>This method is final, as it is merely a shorthand use of the generic way to obtain a spawn,
   * which also depends on the client environment. Subclasses that which to override the way to get
   * a spawn should override the other GetSpawn() methods instead.
   */
  @VisibleForTesting
  public final Spawn getSpawn() throws CommandLineExpansionException {
    return new ActionSpawn(
        commandLines.allArguments(), ImmutableMap.of(), ImmutableList.of(), ImmutableMap.of());
  }

  /**
   * Return a spawn that is representative of the command that this Action will execute in the given
   * client environment.
   */
  public Spawn getSpawn(ActionExecutionContext actionExecutionContext)
      throws CommandLineExpansionException {
    return getSpawn(
        actionExecutionContext.getArtifactExpander(),
        actionExecutionContext.getClientEnv(),
        actionExecutionContext.getInputFilesetMappings());
  }

  Spawn getSpawn(
      ArtifactExpander artifactExpander,
      Map<String, String> clientEnv,
      Map<PathFragment, ImmutableList<FilesetOutputSymlink>> filesetMappings)
      throws CommandLineExpansionException {
    ExpandedCommandLines expandedCommandLines =
        commandLines.expand(artifactExpander, getPrimaryOutput().getExecPath(), commandLineLimits);
    return new ActionSpawn(
        ImmutableList.copyOf(expandedCommandLines.arguments()),
        clientEnv,
        expandedCommandLines.getParamFiles(),
        filesetMappings);
  }

  @Override
  protected void computeKey(ActionKeyContext actionKeyContext, Fingerprint fp)
      throws CommandLineExpansionException {
    fp.addString(GUID);
    commandLines.addToFingerprint(actionKeyContext, fp);
    fp.addString(getMnemonic());
    // We don't need the toolManifests here, because they are a subset of the inputManifests by
    // definition and the output of an action shouldn't change whether something is considered a
    // tool or not.
    fp.addPaths(getRunfilesSupplier().getRunfilesDirs());
    ImmutableList<Artifact> runfilesManifests = getRunfilesSupplier().getManifests();
    fp.addInt(runfilesManifests.size());
    for (Artifact runfilesManifest : runfilesManifests) {
      fp.addPath(runfilesManifest.getExecPath());
    }
    fp.addStringMap(env.getFixedEnv());
    fp.addStrings(getClientEnvironmentVariables());
    fp.addStringMap(getExecutionInfo());
  }

  @Override
  public String describeKey() {
    StringBuilder message = new StringBuilder();
    message.append(getProgressMessage());
    message.append('\n');
    for (Map.Entry<String, String> entry : env.getFixedEnv().entrySet()) {
      message.append("  Environment variable: ");
      message.append(ShellEscaper.escapeString(entry.getKey()));
      message.append('=');
      message.append(ShellEscaper.escapeString(entry.getValue()));
      message.append('\n');
    }
    for (String var : getClientEnvironmentVariables()) {
      message.append("  Environment variables taken from the client environment: ");
      message.append(ShellEscaper.escapeString(var));
      message.append('\n');
    }
    try {
      for (String argument : ShellEscaper.escapeAll(getArguments())) {
        message.append("  Argument: ");
        message.append(argument);
        message.append('\n');
      }
    } catch (CommandLineExpansionException e) {
      message.append("Could not expand command line: ");
      message.append(e);
      message.append('\n');
    }
    return message.toString();
  }

  @Override
  public final String getMnemonic() {
    return mnemonic;
  }

  @Override
  protected String getRawProgressMessage() {
    if (progressMessage != null) {
      return progressMessage.toString();
    }
    return super.getRawProgressMessage();
  }

  @Override
  public ExtraActionInfo.Builder getExtraActionInfo(ActionKeyContext actionKeyContext)
      throws CommandLineExpansionException {
    ExtraActionInfo.Builder builder = super.getExtraActionInfo(actionKeyContext);
    if (extraActionInfoSupplier == null) {
      SpawnInfo spawnInfo = getExtraActionSpawnInfo();
      return builder
          .setExtension(SpawnInfo.spawnInfo, spawnInfo);
    } else {
      extraActionInfoSupplier.extend(builder);
      return builder;
    }
  }

  /**
   * Returns information about this spawn action for use by the extra action mechanism.
   *
   * <p>Subclasses of SpawnAction may override this in order to provide action-specific behaviour.
   * This can be necessary, for example, when the action discovers inputs.
   */
  protected SpawnInfo getExtraActionSpawnInfo() throws CommandLineExpansionException {
    SpawnInfo.Builder info = SpawnInfo.newBuilder();
    Spawn spawn = getSpawn();
    info.addAllArgument(spawn.getArguments());
    for (Map.Entry<String, String> variable : spawn.getEnvironment().entrySet()) {
      info.addVariable(
          EnvironmentVariable.newBuilder()
              .setName(variable.getKey())
              .setValue(variable.getValue())
              .build());
    }
    for (ActionInput input : spawn.getInputFiles()) {
      // Explicitly ignore middleman artifacts here.
      if (!(input instanceof Artifact) || !((Artifact) input).isMiddlemanArtifact()) {
        info.addInputFile(input.getExecPathString());
      }
    }
    info.addAllOutputFile(ActionInputHelper.toExecPaths(spawn.getOutputFiles()));
    return info.build();
  }

  @Override
  @VisibleForTesting
  public final ImmutableMap<String, String> getIncompleteEnvironmentForTesting() {
    // TODO(ulfjack): AbstractAction should declare getEnvironment with a return value of type
    // ActionEnvironment to avoid developers misunderstanding the purpose of this method. That
    // requires first updating all subclasses and callers to actually handle environments correctly,
    // so it's not a small change.
    return env.getFixedEnv();
  }

  /**
   * Returns the out-of-band execution data for this action.
   */
  @Override
  public Map<String, String> getExecutionInfo() {
    return executionInfo;
  }

  /** A spawn instance that is tied to a specific SpawnAction. */
  private class ActionSpawn extends BaseSpawn {

    private final ImmutableList<ActionInput> inputs;
    private final Map<PathFragment, ImmutableList<FilesetOutputSymlink>> filesetMappings;
    private final ImmutableMap<String, String> effectiveEnvironment;

    /**
     * Creates an ActionSpawn with the given environment variables.
     *
     * <p>Subclasses of ActionSpawn may subclass in order to provide action-specific values for
     * environment variables or action inputs.
     */
    private ActionSpawn(
        ImmutableList<String> arguments,
        Map<String, String> clientEnv,
        Iterable<? extends ActionInput> additionalInputs,
        Map<PathFragment, ImmutableList<FilesetOutputSymlink>> filesetMappings) {
      super(
          arguments,
          ImmutableMap.<String, String>of(),
          executionInfo,
          SpawnAction.this.getRunfilesSupplier(),
          SpawnAction.this,
          resourceSet);
      ImmutableList.Builder<ActionInput> inputs = ImmutableList.builder();
      ImmutableList<Artifact> manifests = getRunfilesSupplier().getManifests();
      for (Artifact input : getInputs()) {
        if (!input.isFileset() && !manifests.contains(input)) {
          inputs.add(input);
        }
      }
      inputs.addAll(additionalInputs);
      this.inputs = inputs.build();
      this.filesetMappings = filesetMappings;
      LinkedHashMap<String, String> env = new LinkedHashMap<>(SpawnAction.this.env.size());
      SpawnAction.this.env.resolve(env, clientEnv);
      effectiveEnvironment = ImmutableMap.copyOf(env);
    }

    @Override
    public ImmutableMap<String, String> getEnvironment() {
      return effectiveEnvironment;
    }

    @Override
    public ImmutableMap<PathFragment, ImmutableList<FilesetOutputSymlink>> getFilesetMappings() {
      return ImmutableMap.copyOf(filesetMappings);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Iterable<? extends ActionInput> getInputFiles() {
      return inputs;
    }
  }

  /**
   * Builder class to construct {@link SpawnAction} instances.
   */
  public static class Builder {

    private final NestedSetBuilder<Artifact> toolsBuilder = NestedSetBuilder.stableOrder();
    private final NestedSetBuilder<Artifact> inputsBuilder = NestedSetBuilder.stableOrder();
    private final List<Artifact> outputs = new ArrayList<>();
    private final List<RunfilesSupplier> inputRunfilesSuppliers = new ArrayList<>();
    private final List<RunfilesSupplier> toolRunfilesSuppliers = new ArrayList<>();
    private ResourceSet resourceSet = AbstractAction.DEFAULT_RESOURCE_SET;
    private ImmutableMap<String, String> environment = ImmutableMap.of();
    private ImmutableSet<String> inheritedEnvironment = ImmutableSet.of();
    private ImmutableMap<String, String> executionInfo = ImmutableMap.of();
    private boolean isShellCommand = false;
    private boolean useDefaultShellEnvironment = false;
    protected boolean executeUnconditionally;
    private PathFragment executable;
    // executableArgs does not include the executable itself.
    private List<String> executableArgs;
    private List<CommandLineAndParamFileInfo> commandLines = new ArrayList<>();

    private CharSequence progressMessage;
    private String mnemonic = "Unknown";
    protected ExtraActionInfoSupplier extraActionInfoSupplier = null;
    private boolean disableSandboxing = false;

    /**
     * Creates a SpawnAction builder.
     */
    public Builder() {}

    /**
     * Creates a builder that is a copy of another builder.
     */
    public Builder(Builder other) {
      this.toolsBuilder.addTransitive(other.toolsBuilder.build());
      this.inputsBuilder.addTransitive(other.inputsBuilder.build());
      this.outputs.addAll(other.outputs);
      this.inputRunfilesSuppliers.addAll(other.inputRunfilesSuppliers);
      this.toolRunfilesSuppliers.addAll(other.toolRunfilesSuppliers);
      this.resourceSet = other.resourceSet;
      this.environment = other.environment;
      this.executionInfo = other.executionInfo;
      this.isShellCommand = other.isShellCommand;
      this.useDefaultShellEnvironment = other.useDefaultShellEnvironment;
      this.executable = other.executable;
      this.executableArgs = (other.executableArgs != null)
          ? Lists.newArrayList(other.executableArgs)
          : null;
      this.commandLines = new ArrayList<>(other.commandLines);
      this.progressMessage = other.progressMessage;
      this.mnemonic = other.mnemonic;
    }

    /**
     * Builds the SpawnAction and ParameterFileWriteAction (if param file is used) using the passed-
     * in action configuration. The first item of the returned array is always the SpawnAction
     * itself.
     *
     * <p>This method makes a copy of all the collections, so it is safe to reuse the builder after
     * this method returns.
     *
     * <p>This is annotated with @CheckReturnValue, which causes a compiler error when you call this
     * method and ignore its return value. This is because some time ago, calling .build() had the
     * side-effect of registering it with the RuleContext that was passed in to the constructor.
     * This logic was removed, but if people don't notice and still rely on the side-effect, things
     * may break.
     *
     * @return the SpawnAction and any actions required by it, with the first item always being the
     *      SpawnAction itself.
     */
    @CheckReturnValue
    public Action[] build(ActionConstructionContext context) {
      return build(context.getActionOwner(), context.getAnalysisEnvironment(),
          context.getConfiguration());
    }

    @VisibleForTesting @CheckReturnValue
    public Action[] build(ActionOwner owner, AnalysisEnvironment analysisEnvironment,
        BuildConfiguration configuration) {
      final Action[] actions;
      final CommandLines commandLines;
      ImmutableList<String> executableArgs = buildExecutableArgs();
      if (configuration.deferParamFiles()) {
        actions = new Action[1];
        CommandLines.Builder result = CommandLines.builder();
        result.addCommandLine(CommandLine.of(executableArgs));
        for (CommandLineAndParamFileInfo pair : this.commandLines) {
          result.addCommandLine(pair);
        }
        commandLines = result.build();
      } else {
        List<Action> paramFileActions = new ArrayList<>(this.commandLines.size());
        CommandLine commandLine =
            buildCommandLinesAndParamFileActions(
                owner,
                analysisEnvironment,
                configuration,
                executableArgs,
                this.commandLines,
                paramFileActions);
        commandLines = CommandLines.of(commandLine);
        actions = new Action[1 + paramFileActions.size()];
        for (int i = 0; i < paramFileActions.size(); ++i) {
          actions[i + 1] = paramFileActions.get(i);
        }
      }
      ActionEnvironment env =
          useDefaultShellEnvironment
              ? configuration.getActionEnvironment()
              : ActionEnvironment.create(environment, inheritedEnvironment);
      Action spawnAction =
          buildSpawnAction(owner, commandLines, configuration.getCommandLineLimits(), env);
      actions[0] = spawnAction;
      return actions;
    }

    @CheckReturnValue
    SpawnAction buildForActionTemplate(ActionOwner owner) {
      CommandLines.Builder result = CommandLines.builder();
      ImmutableList<String> executableArgs = buildExecutableArgs();
      result.addCommandLine(CommandLine.of(executableArgs));
      for (CommandLineAndParamFileInfo pair : commandLines) {
        result.addCommandLine(pair.commandLine);
      }
      return buildSpawnAction(
          owner,
          result.build(),
          CommandLineLimits.UNLIMITED,
          ActionEnvironment.create(environment, inheritedEnvironment));
    }

    private CommandLine buildCommandLinesAndParamFileActions(
        ActionOwner owner,
        AnalysisEnvironment analysisEnvironment,
        BuildConfiguration configuration,
        ImmutableList<String> executableArgs,
        List<CommandLineAndParamFileInfo> commandLines,
        List<Action> paramFileActions) {
      boolean hasConditionalParamFile =
          commandLines.stream().anyMatch(c -> c.paramFileInfo != null && !c.paramFileInfo.always());
      boolean spillToParamFiles = false;
      if (hasConditionalParamFile) {
        int totalLen = getParamFileSize(executableArgs);
        for (CommandLineAndParamFileInfo commandLineAndParamFileInfo : commandLines) {
          totalLen += getCommandLineSize(commandLineAndParamFileInfo.commandLine);
        }
        // To reduce implementation complexity we either spill all or none of the param files.
        spillToParamFiles = totalLen >= configuration.getCommandLineLimits().maxLength;
      }
      // We a name based on the output, starting at <output>-2.params
      // and then incrementing
      int paramFileNameSuffix = 2;
      SpawnActionCommandLine.Builder result = new SpawnActionCommandLine.Builder();
      result.addExecutableArguments(executableArgs);
      for (CommandLineAndParamFileInfo commandLineAndParamFileInfo : commandLines) {
        CommandLine commandLine = commandLineAndParamFileInfo.commandLine;
        ParamFileInfo paramFileInfo = commandLineAndParamFileInfo.paramFileInfo;
        boolean useParamsFile =
            paramFileInfo != null && (paramFileInfo.always() || spillToParamFiles);
        if (useParamsFile) {
          Artifact output = Iterables.getFirst(outputs, null);
          Preconditions.checkNotNull(output);
          PathFragment paramFilePath =
              ParameterFile.derivePath(
                  output.getRootRelativePath(), Integer.toString(paramFileNameSuffix));
          Artifact paramFile =
              analysisEnvironment.getDerivedArtifact(paramFilePath, output.getRoot());
          inputsBuilder.add(paramFile);
          ParameterFileWriteAction paramFileWriteAction =
              new ParameterFileWriteAction(
                  owner,
                  paramFileInfo.getInputs(),
                  paramFile,
                  commandLine,
                  paramFileInfo.getFileType(),
                  paramFileInfo.getCharset());
          paramFileActions.add(paramFileWriteAction);
          ++paramFileNameSuffix;
          result.addParamFile(paramFile, paramFileInfo);
        } else {
          result.addCommandLine(commandLine);
        }
      }
      return result.build();
    }

    private static int getCommandLineSize(CommandLine commandLine) {
      try {
        Iterable<String> actualArguments = commandLine.arguments();
        return getParamFileSize(actualArguments);
      } catch (CommandLineExpansionException e) {
        // CommandLineExpansionException is thrown deterministically. We can ignore
        // it here and pretend that a params file is not necessary at this stage,
        // and an error will be thrown later at execution time.
        return 0;
      }
    }

    private static int getParamFileSize(Iterable<String> args) {
      int size = 0;
      for (String s : args) {
        size += s.length() + 1; // Account for the space character
      }
      return size;
    }

    /**
     * Builds the SpawnAction using the passed-in action configuration.
     *
     * <p>This method makes a copy of all the collections, so it is safe to reuse the builder after
     * this method returns.
     */
    private SpawnAction buildSpawnAction(
        ActionOwner owner,
        CommandLines commandLines,
        CommandLineLimits commandLineLimits,
        ActionEnvironment env) {
      NestedSet<Artifact> tools = toolsBuilder.build();

      // Tools are by definition a subset of the inputs, so make sure they're present there, too.
      NestedSet<Artifact> inputsAndTools =
          NestedSetBuilder.<Artifact>stableOrder()
              .addTransitive(inputsBuilder.build())
              .addTransitive(tools)
              .build();

      if (disableSandboxing) {
        ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
        builder.putAll(executionInfo);
        builder.put("nosandbox", "1");
        executionInfo = builder.build();
      }

      return createSpawnAction(
          owner,
          tools,
          inputsAndTools,
          ImmutableList.copyOf(outputs),
          outputs.get(0),
          resourceSet,
          commandLines,
          commandLineLimits,
          isShellCommand,
          env,
          ImmutableMap.copyOf(executionInfo),
          progressMessage,
          new CompositeRunfilesSupplier(
              Iterables.concat(this.inputRunfilesSuppliers, this.toolRunfilesSuppliers)),
          mnemonic);
    }

    /** Creates a SpawnAction. */
    protected SpawnAction createSpawnAction(
        ActionOwner owner,
        NestedSet<Artifact> tools,
        NestedSet<Artifact> inputsAndTools,
        ImmutableList<Artifact> outputs,
        Artifact primaryOutput,
        ResourceSet resourceSet,
        CommandLines commandLines,
        CommandLineLimits commandLineLimits,
        boolean isShellCommand,
        ActionEnvironment env,
        ImmutableMap<String, String> executionInfo,
        CharSequence progressMessage,
        RunfilesSupplier runfilesSupplier,
        String mnemonic) {
      return new SpawnAction(
          owner,
          tools,
          inputsAndTools,
          outputs,
          primaryOutput,
          resourceSet,
          commandLines,
          commandLineLimits,
          isShellCommand,
          env,
          executionInfo,
          progressMessage,
          runfilesSupplier,
          mnemonic,
          executeUnconditionally,
          extraActionInfoSupplier);
    }

    private ImmutableList<String> buildExecutableArgs() {
      Preconditions.checkNotNull(executable);
      Preconditions.checkNotNull(executableArgs);

      return ImmutableList.<String>builder()
          .add(executable.getPathString())
          .addAll(executableArgs)
          .build();
    }

    /**
     * Adds an artifact that is necessary for executing the spawn itself (e.g. a compiler), in
     * contrast to an artifact that is necessary for the spawn to do its work (e.g. source code).
     *
     * <p>The artifact is implicitly added to the inputs of the action as well.
     */
    public Builder addTool(Artifact tool) {
      toolsBuilder.add(tool);
      return this;
    }

    /**
     * Adds an input to this action.
     */
    public Builder addInput(Artifact artifact) {
      inputsBuilder.add(artifact);
      return this;
    }

    /**
     * Adds tools to this action.
     */
    public Builder addTools(Iterable<Artifact> artifacts) {
      toolsBuilder.addAll(artifacts);
      return this;
    }

    /** Adds tools to this action. */
    public Builder addTransitiveTools(NestedSet<Artifact> artifacts) {
      toolsBuilder.addTransitive(artifacts);
      return this;
    }

    /**
     * Adds inputs to this action.
     */
    public Builder addInputs(Iterable<Artifact> artifacts) {
      inputsBuilder.addAll(artifacts);
      return this;
    }

    /** @deprecated Use {@link #addTransitiveInputs} to avoid excessive memory use. */
    @Deprecated
    public Builder addInputs(NestedSet<Artifact> artifacts) {
      // Do not delete this method, or else addInputs(Iterable) calls with a NestedSet argument
      // will not be flagged.
      inputsBuilder.addAll((Iterable<Artifact>) artifacts);
      return this;
    }

    /**
     * Adds transitive inputs to this action.
     */
    public Builder addTransitiveInputs(NestedSet<Artifact> artifacts) {
      inputsBuilder.addTransitive(artifacts);
      return this;
    }

    public Builder addRunfilesSupplier(RunfilesSupplier supplier) {
      inputRunfilesSuppliers.add(supplier);
      return this;
    }

    public Builder addOutput(Artifact artifact) {
      outputs.add(artifact);
      return this;
    }

    public Builder addOutputs(Iterable<Artifact> artifacts) {
      Iterables.addAll(outputs, artifacts);
      return this;
    }

    /**
     * Checks whether the action produces any outputs
     */
    public boolean hasOutputs() {
      return !outputs.isEmpty();
    }

    public Builder setResources(ResourceSet resourceSet) {
      this.resourceSet = resourceSet;
      return this;
    }

    /**
     * Sets the map of environment variables. Do not use! This makes the builder ignore the
     * 'default shell environment', which is computed from the --action_env command line option.
     */
    public Builder setEnvironment(Map<String, String> environment) {
      this.environment = ImmutableMap.copyOf(environment);
      this.useDefaultShellEnvironment = false;
      return this;
    }

    /**
     * Sets the set of inherited environment variables. Do not use! This makes the builder ignore
     * the 'default shell environment', which is computed from the --action_env command line option.
     */
    public Builder setInheritedEnvironment(Iterable<String> inheritedEnvironment) {
      this.inheritedEnvironment = ImmutableSet.copyOf(inheritedEnvironment);
      this.useDefaultShellEnvironment = false;
      return this;
    }

    /**
     * Sets the map of execution info.
     */
    public Builder setExecutionInfo(Map<String, String> info) {
      this.executionInfo = ImmutableMap.copyOf(info);
      return this;
    }

    /**
     * Sets the environment to the configuration's default shell environment.
     *
     * <p><b>All actions should set this if possible and avoid using {@link #setEnvironment}.</b>
     *
     * <p>When this property is set, the action will use a minimal, standardized environment map.
     *
     * <p>The list of envvars available to the action (the keys in this map) comes from two places:
     * from the configuration fragments ({@link BuildConfiguration.Fragment#setupActionEnvironment})
     * and from the command line or rc-files via {@code --action_env} flags.
     *
     * <p>The values for these variables may come from one of three places: from the configuration
     * fragment, or from the {@code --action_env} flag (when the flag specifies a name-value pair,
     * e.g. {@code --action_env=FOO=bar}), or from the client environment (when the flag only
     * specifies a name, e.g. {@code --action_env=HOME}).
     *
     * <p>The client environment is specified by the {@code --client_env} flags. The Bazel client
     * passes these flags to the Bazel server upon each build (e.g.
     * {@code --client_env=HOME=/home/johndoe}), so the server can keep track of environmental
     * changes between builds, and always use the up-to-date environment (as opposed to calling
     * {@code System.getenv}, which it should never do, though as of 2017-08-02 it still does in a
     * few places).
     *
     * <p>The {@code --action_env} has priority over configuration-fragment-dictated envvar values,
     * i.e. if the configuration fragment tries to add FOO=bar to the environment, and there's also
     * {@link --action_env=FOO=baz} or {@link --action_env=FOO}, then FOO will be available to the
     * action and its value will be "baz", or whatever the corresponding {@code --client_env} flag
     * specified, respectively.
     *
     * @see {@link BuildConfiguration#getLocalShellEnvironment}
     */
    public Builder useDefaultShellEnvironment() {
      this.environment = null;
      this.inheritedEnvironment = null;
      this.useDefaultShellEnvironment = true;
      return this;
    }

    /**
     * Makes the action always execute, even if none of its inputs have changed.
     *
     * <p>Only use this when absolutely necessary, since this is a performance hit and we'd like to
     * get rid of this mechanism eventually. You'll eventually be able to declare a Skyframe
     * dependency on the build ID, which would accomplish the same thing.
     */
    public Builder executeUnconditionally() {
      // This should really be implemented by declaring a Skyframe dependency on the build ID
      // instead, however, we can't just do that yet from within actions, so we need to go through
      // Action.executeUnconditionally() which in turn is called by ActionCacheChecker.
      this.executeUnconditionally = true;
      return this;
    }

    /**
     * Sets the executable path; the path is interpreted relative to the
     * execution root.
     *
     * <p>Calling this method overrides any previous values set via calls to
     * {@link #setExecutable(Artifact)}, {@link #setJavaExecutable}, or
     * {@link #setShellCommand(String)}.
     */
    public Builder setExecutable(PathFragment executable) {
      this.executable = executable;
      this.executableArgs = Lists.newArrayList();
      this.isShellCommand = false;
      return this;
    }

    /**
     * Sets the executable as an artifact.
     *
     * <p>Calling this method overrides any previous values set via calls to
     * {@link #setExecutable(Artifact)}, {@link #setJavaExecutable}, or
     * {@link #setShellCommand(String)}.
     */
    public Builder setExecutable(Artifact executable) {
      addTool(executable);
      return setExecutable(executable.getExecPath());
    }

    /**
     * Sets the executable as a configured target. Automatically adds the files to run to the tools
     * and inputs and uses the executable of the target as the executable.
     *
     * <p>Calling this method overrides any previous values set via calls to
     * {@link #setExecutable(Artifact)}, {@link #setJavaExecutable}, or
     * {@link #setShellCommand(String)}.
     */
    public Builder setExecutable(TransitiveInfoCollection executable) {
      FilesToRunProvider provider = executable.getProvider(FilesToRunProvider.class);
      Preconditions.checkArgument(provider != null);
      return setExecutable(provider);
    }

    /**
     * Sets the executable as a configured target. Automatically adds the files to run to the tools
     * and inputs and uses the executable of the target as the executable.
     *
     * <p>Calling this method overrides any previous values set via calls to {@link #setExecutable},
     * {@link #setJavaExecutable}, or {@link #setShellCommand(String)}.
     */
    public Builder setExecutable(FilesToRunProvider executableProvider) {
      Preconditions.checkArgument(executableProvider.getExecutable() != null,
          "The target does not have an executable");
      setExecutable(executableProvider.getExecutable().getExecPath());
      return addTool(executableProvider);
    }

    private Builder setJavaExecutable(PathFragment javaExecutable, Artifact deployJar,
        List<String> jvmArgs, String... launchArgs) {
      this.executable = javaExecutable;
      this.executableArgs = Lists.newArrayList();
      executableArgs.add("-Xverify:none");
      executableArgs.addAll(jvmArgs);
      Collections.addAll(executableArgs, launchArgs);
      toolsBuilder.add(deployJar);
      this.isShellCommand = false;
      return this;
    }

    /**
     * Sets the executable to be a java class executed from the given deploy
     * jar. The deploy jar is automatically added to the action inputs.
     *
     * <p>Calling this method overrides any previous values set via calls to
     * {@link #setExecutable}, {@link #setJavaExecutable}, or
     * {@link #setShellCommand(String)}.
     */
    public Builder setJavaExecutable(PathFragment javaExecutable,
        Artifact deployJar, String javaMainClass, List<String> jvmArgs) {
      return setJavaExecutable(javaExecutable, deployJar, jvmArgs, "-cp",
          deployJar.getExecPathString(), javaMainClass);
    }

    /**
     * Sets the executable to be a jar executed from the given deploy jar. The deploy jar is
     * automatically added to the action inputs.
     *
     * <p>This method is similar to {@link #setJavaExecutable} but it assumes that the Jar artifact
     * declares a main class.
     *
     * <p>Calling this method overrides any previous values set via calls to {@link #setExecutable},
     * {@link #setJavaExecutable}, or {@link #setShellCommand(String)}.
     */
    public Builder setJarExecutable(PathFragment javaExecutable,
        Artifact deployJar, List<String> jvmArgs) {
      return setJavaExecutable(javaExecutable, deployJar, jvmArgs, "-jar",
          deployJar.getExecPathString());
    }

    /**
     * Sets the executable to be the shell and adds the given command as the command to be executed.
     *
     * <p>Note that this will not clear the arguments, so any arguments will be passed in addition
     * to the command given here.
     *
     * <p>Calling this method overrides any previous values set via calls to {@link
     * #setExecutable(Artifact)}, {@link #setJavaExecutable}, or {@link #setShellCommand(String)}.
     */
    public Builder setShellCommand(PathFragment shExecutable, String command) {
      this.executable = shExecutable;
      // 0=shell command switch, 1=command
      this.executableArgs = Lists.newArrayList("-c", command);
      this.isShellCommand = true;
      return this;
    }

    /**
     * Sets the executable to be the shell and adds the given interned commands as the
     * commands to be executed.
     */
    public Builder setShellCommand(Iterable<String> command) {
      this.executable = PathFragment.create(Iterables.getFirst(command, null));
      // The first item of the commands is the shell executable that should be used.
      this.executableArgs = ImmutableList.copyOf(Iterables.skip(command, 1));
      this.isShellCommand = true;
      return this;
    }

    /**
     * Adds an executable and its runfiles, which is necessary for executing the spawn itself (e.g.
     * a compiler), in contrast to artifacts that are necessary for the spawn to do its work (e.g.
     * source code).
     */
    public Builder addTool(FilesToRunProvider tool) {
      addTransitiveTools(tool.getFilesToRun());
      toolRunfilesSuppliers.add(tool.getRunfilesSupplier());
      return this;
    }

    /**
     * Appends the arguments to the list of executable arguments.
     */
    public Builder addExecutableArguments(String... arguments) {
      Preconditions.checkState(executableArgs != null);
      Collections.addAll(executableArgs, arguments);
      return this;
    }

    /**
     * Add multiple arguments in the order they are returned by the collection
     * to the list of executable arguments.
     */
    public Builder addExecutableArguments(Iterable<String> arguments) {
      Preconditions.checkState(executableArgs != null);
      Iterables.addAll(executableArgs, arguments);
      return this;
    }

    /**
     * Adds a delegate to compute the command line at a later time.
     *
     * <p>The arguments are added after the executable arguments. If you add multiple command lines,
     * they are expanded in the corresponding order.
     *
     * <p>The main intention of this method is to save memory by allowing client-controlled sharing
     * between actions and configured targets. Objects passed to this method MUST be immutable.
     *
     * <p>See also {@link CustomCommandLine}.
     */
    public Builder addCommandLine(CommandLine commandLine) {
      this.commandLines.add(new CommandLineAndParamFileInfo(commandLine, null));
      return this;
    }

    /**
     * Adds a delegate to compute the command line at a later time, optionally spilled to a params
     * file.
     *
     * <p>The arguments are added after the executable arguments. If you add multiple command lines,
     * they are expanded in the corresponding order. If the command line is spilled to a params
     * file, it is replaced with an argument pointing to the param file.
     *
     * <p>The main intention of this method is to save memory by allowing client-controlled sharing
     * between actions and configured targets. Objects passed to this method MUST be immutable.
     *
     * <p>See also {@link CustomCommandLine}.
     */
    public Builder addCommandLine(CommandLine commandLine, @Nullable ParamFileInfo paramFileInfo) {
      this.commandLines.add(new CommandLineAndParamFileInfo(commandLine, paramFileInfo));
      return this;
    }

    /**
     * Sets the progress message.
     *
     * <p>If you are formatting the string in any way, prefer one of the overloads that do the
     * formatting lazily. This helps save memory by delaying the construction of the progress
     * message string.
     *
     * <p>If you cannot use simple formatting, try {@link Builder#setProgressMessage(LazyString)}.
     *
     * <p>If you must eagerly compute the string, use {@link Builder#setProgressMessageNonLazy}.
     */
    public Builder setProgressMessage(@CompileTimeConstant String progressMessage) {
      this.progressMessage = progressMessage;
      return this;
    }

    /**
     * Sets the progress message. The string is lazily evaluated.
     *
     * @param progressMessage The message to display
     * @param subject Passed to {@link String#format}
     */
    @FormatMethod
    public Builder setProgressMessage(@FormatString String progressMessage, Object subject) {
      return setProgressMessage(
          new LazyString() {
            @Override
            public String toString() {
              return String.format(progressMessage, subject);
            }
          });
    }

    /**
     * Sets the progress message. The string is lazily evaluated.
     *
     * @param progressMessage The message to display
     * @param subject0 Passed to {@link String#format}
     * @param subject1 Passed to {@link String#format}
     */
    @FormatMethod
    public Builder setProgressMessage(
        @FormatString String progressMessage, Object subject0, Object subject1) {
      return setProgressMessage(
          new LazyString() {
            @Override
            public String toString() {
              return String.format(progressMessage, subject0, subject1);
            }
          });
    }

    /**
     * Sets the progress message. The string is lazily evaluated.
     *
     * @param progressMessage The message to display
     * @param subject0 Passed to {@link String#format}
     * @param subject1 Passed to {@link String#format}
     * @param subject2 Passed to {@link String#format}
     */
    @FormatMethod
    public Builder setProgressMessage(
        @FormatString String progressMessage, Object subject0, Object subject1, Object subject2) {
      return setProgressMessage(
          new LazyString() {
            @Override
            public String toString() {
              return String.format(progressMessage, subject0, subject1, subject2);
            }
          });
    }

    /**
     * Sets the progress message. The string is lazily evaluated.
     *
     * @param progressMessage The message to display
     * @param subject0 Passed to {@link String#format}
     * @param subject1 Passed to {@link String#format}
     * @param subject2 Passed to {@link String#format}
     * @param subject3 Passed to {@link String#format}
     */
    @FormatMethod
    public Builder setProgressMessage(
        @FormatString String progressMessage,
        Object subject0,
        Object subject1,
        Object subject2,
        Object subject3) {
      return setProgressMessage(
          new LazyString() {
            @Override
            public String toString() {
              return String.format(progressMessage, subject0, subject1, subject2, subject3);
            }
          });
    }

    /**
     * Sets a lazily computed progress message.
     *
     * <p>When possible, prefer use of one of the overloads that use {@link String#format}. If you
     * do use this overload, take care not to capture anything expensive.
     */
    public Builder setProgressMessage(LazyString progressMessage) {
      this.progressMessage = progressMessage;
      return this;
    }

    /**
     * Sets an eagerly computed progress message.
     *
     * <p>Prefer one of the lazy overloads whenever possible, as it will generally save memory.
     */
    public Builder setProgressMessageNonLazy(String progressMessage) {
      this.progressMessage = progressMessage;
      return this;
    }

    public Builder setMnemonic(String mnemonic) {
      Preconditions.checkArgument(
          !mnemonic.isEmpty() && CharMatcher.javaLetterOrDigit().matchesAllOf(mnemonic),
          "mnemonic must only contain letters and/or digits, and have non-zero length, was: \"%s\"",
          mnemonic);
      this.mnemonic = mnemonic;
      return this;
    }

    public <T> Builder setExtraActionInfo(ExtraActionInfoSupplier extraActionInfoSupplier) {
      this.extraActionInfoSupplier = extraActionInfoSupplier;
      return this;
    }

    public Builder disableSandboxing() {
      this.disableSandboxing = true;
      return this;
    }
  }

  /**
   * Command line implementation that optimises for containing executable args, command lines, and
   * command lines spilled to param files.
   */
  @AutoCodec
  static class SpawnActionCommandLine extends CommandLine {
    private final Object[] values;

    SpawnActionCommandLine(Object[] values) {
      this.values = values;
    }

    @Override
    public Iterable<String> arguments() throws CommandLineExpansionException {
      return expandArguments(null);
    }

    @Override
    public Iterable<String> arguments(ArtifactExpander artifactExpander)
        throws CommandLineExpansionException {
      return expandArguments(artifactExpander);
    }

    private Iterable<String> expandArguments(@Nullable ArtifactExpander artifactExpander)
        throws CommandLineExpansionException {
      ImmutableList.Builder<String> result = ImmutableList.builder();
      int count = values.length;
      for (int i = 0; i < count; ++i) {
        Object value = values[i];
        if (value instanceof String) {
          result.add((String) value);
        } else if (value instanceof Artifact) {
          Artifact paramFile = (Artifact) value;
          String flagFormatString = (String) values[++i];
          result.add(
              SingleStringArgFormatter.format(flagFormatString, paramFile.getExecPathString()));
        } else if (value instanceof CommandLine) {
          CommandLine commandLine = (CommandLine) value;
          if (artifactExpander != null) {
            result.addAll(commandLine.arguments(artifactExpander));
          } else {
            result.addAll(commandLine.arguments());
          }
        }
      }
      return result.build();
    }

    private static class Builder {
      private List<Object> values = new ArrayList<>();

      Builder addExecutableArguments(ImmutableList<String> executableArguments) {
        values.addAll(executableArguments);
        return this;
      }

      Builder addParamFile(Artifact paramFile, ParamFileInfo paramFileInfo) {
        values.add(paramFile);
        values.add(paramFileInfo.getFlagFormatString());
        return this;
      }

      Builder addCommandLine(CommandLine commandLine) {
        values.add(commandLine);
        return this;
      }

      SpawnActionCommandLine build() {
        return new SpawnActionCommandLine(values.toArray());
      }
    }
  }
}
