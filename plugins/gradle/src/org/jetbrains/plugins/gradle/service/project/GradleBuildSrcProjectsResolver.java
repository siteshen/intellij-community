// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.plugins.gradle.service.project;

import com.intellij.openapi.externalSystem.model.DataNode;
import com.intellij.openapi.externalSystem.model.ProjectKeys;
import com.intellij.openapi.externalSystem.model.project.*;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.ArrayUtil;
import com.intellij.util.Function;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.MultiMap;
import org.gradle.tooling.ProjectConnection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.gradle.model.data.BuildParticipant;
import org.jetbrains.plugins.gradle.model.data.BuildScriptClasspathData;
import org.jetbrains.plugins.gradle.model.data.CompositeBuildData;
import org.jetbrains.plugins.gradle.model.data.GradleSourceSetData;
import org.jetbrains.plugins.gradle.settings.DistributionType;
import org.jetbrains.plugins.gradle.settings.GradleExecutionSettings;
import org.jetbrains.plugins.gradle.util.GradleConstants;

import java.io.File;
import java.util.*;

import static com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil.*;
import static org.jetbrains.plugins.gradle.service.project.GradleProjectResolverUtil.getDefaultModuleTypeId;

/**
 * @author Vladislav.Soroka
 */
public class GradleBuildSrcProjectsResolver {

  @NotNull
  private final GradleProjectResolver myProjectResolver;
  @NotNull
  private final DefaultProjectResolverContext myResolverContext;
  @Nullable
  private final File myGradleUserHome;
  @Nullable
  private final GradleExecutionSettings myMainBuildExecutionSettings;
  @NotNull
  private final ExternalSystemTaskNotificationListener myListener;
  @NotNull
  private final ExternalSystemTaskId mySyncTaskId;
  @NotNull
  private final GradleProjectResolverExtension myResolverChain;

  public GradleBuildSrcProjectsResolver(@NotNull GradleProjectResolver projectResolver,
                                        @NotNull DefaultProjectResolverContext resolverContext,
                                        @Nullable File gradleUserHome,
                                        @Nullable GradleExecutionSettings mainBuildSettings,
                                        @NotNull ExternalSystemTaskNotificationListener listener,
                                        @NotNull ExternalSystemTaskId syncTaskId,
                                        @NotNull GradleProjectResolverExtension projectResolverChain) {
    myProjectResolver = projectResolver;
    myResolverContext = resolverContext;
    myGradleUserHome = gradleUserHome;
    myMainBuildExecutionSettings = mainBuildSettings;
    myListener = listener;
    mySyncTaskId = syncTaskId;
    myResolverChain = projectResolverChain;
  }

  public void discoverAndAppendTo(@NotNull DataNode<ProjectData> mainBuildProjectDataNode) {
    String gradleHome = myGradleUserHome == null ? null : myGradleUserHome.getPath();
    ProjectData mainBuildProjectData = mainBuildProjectDataNode.getData();
    String projectPath = mainBuildProjectData.getLinkedExternalProjectPath();

    Map<String, String> includedBuildsPaths = ContainerUtil.newHashMap();
    Map<String, String> buildNames = ContainerUtil.newHashMap();
    buildNames.put(projectPath, mainBuildProjectData.getExternalName());
    DataNode<CompositeBuildData> compositeBuildData = find(mainBuildProjectDataNode, CompositeBuildData.KEY);
    if (compositeBuildData != null) {
      for (BuildParticipant buildParticipant : compositeBuildData.getData().getCompositeParticipants()) {
        String buildParticipantRootPath = buildParticipant.getRootPath();
        buildNames.put(buildParticipantRootPath, buildParticipant.getRootProjectName());
        for (String path : buildParticipant.getProjects()) {
          includedBuildsPaths.put(path, buildParticipantRootPath);
        }
      }
    }

    MultiMap<String, DataNode<BuildScriptClasspathData>> buildClasspathNodesMap = MultiMap.createSmart();
    Map<String, ModuleData> existingProjectsPaths = ContainerUtil.newHashMap();
    for (DataNode<ModuleData> moduleDataNode : findAll(mainBuildProjectDataNode, ProjectKeys.MODULE)) {
      String path = moduleDataNode.getData().getLinkedExternalProjectPath();
      existingProjectsPaths.put(path, moduleDataNode.getData());
      DataNode<BuildScriptClasspathData> scriptClasspathDataNode = find(moduleDataNode, BuildScriptClasspathData.KEY);
      if (scriptClasspathDataNode != null) {
        String rootPath = includedBuildsPaths.get(path);
        buildClasspathNodesMap.putValue(rootPath != null ? rootPath : projectPath, scriptClasspathDataNode);
      }
    }

    for (String buildPath : buildClasspathNodesMap.keySet()) {
      Collection<DataNode<BuildScriptClasspathData>> buildClasspathNodes = buildClasspathNodesMap.get(buildPath);

      GradleExecutionSettings buildSrcProjectSettings;
      if (gradleHome != null) {
        if (myMainBuildExecutionSettings != null) {
          buildSrcProjectSettings = new GradleExecutionSettings(gradleHome,
                                                                myMainBuildExecutionSettings.getServiceDirectory(),
                                                                DistributionType.LOCAL,
                                                                myMainBuildExecutionSettings.isOfflineWork());
          buildSrcProjectSettings.setIdeProjectPath(myMainBuildExecutionSettings.getIdeProjectPath());
          buildSrcProjectSettings.setJavaHome(myMainBuildExecutionSettings.getJavaHome());
          buildSrcProjectSettings.setResolveModulePerSourceSet(myMainBuildExecutionSettings.isResolveModulePerSourceSet());
          buildSrcProjectSettings.setUseQualifiedModuleNames(myMainBuildExecutionSettings.isUseQualifiedModuleNames());
          buildSrcProjectSettings.setRemoteProcessIdleTtlInMs(myMainBuildExecutionSettings.getRemoteProcessIdleTtlInMs());
          buildSrcProjectSettings.setVerboseProcessing(myMainBuildExecutionSettings.isVerboseProcessing());
          buildSrcProjectSettings.setWrapperPropertyFile(myMainBuildExecutionSettings.getWrapperPropertyFile());
          buildSrcProjectSettings.withArguments(myMainBuildExecutionSettings.getArguments())
                                 .withEnvironmentVariables(myMainBuildExecutionSettings.getEnv())
                                 .passParentEnvs(myMainBuildExecutionSettings.isPassParentEnvs())
                                 .withVmOptions(myMainBuildExecutionSettings.getVmOptions());
        }
        else {
          buildSrcProjectSettings = new GradleExecutionSettings(gradleHome, null, DistributionType.LOCAL, false);
        }
      }
      else {
        buildSrcProjectSettings = myMainBuildExecutionSettings;
      }

      final String buildSrcProjectPath = buildPath + "/buildSrc";
      DefaultProjectResolverContext buildSrcResolverCtx =
        new DefaultProjectResolverContext(mySyncTaskId, buildSrcProjectPath, buildSrcProjectSettings, myListener, false);
      myResolverContext.copyUserDataTo(buildSrcResolverCtx);
      String buildName = buildNames.get(buildPath);

      ModuleData moduleData = existingProjectsPaths.get(buildPath);
      String buildSrcGroup = getBuildSrcGroup(buildPath, buildName, moduleData);

      buildSrcResolverCtx.setDefaultGroupId(buildSrcGroup);
      handleBuildSrcProject(mainBuildProjectDataNode,
                            buildName,
                            buildClasspathNodes,
                            existingProjectsPaths,
                            buildSrcResolverCtx,
                            myProjectResolver.getProjectDataFunction(buildSrcResolverCtx, myResolverChain, true));
    }
  }

  private void handleBuildSrcProject(@NotNull DataNode<ProjectData> resultProjectDataNode,
                                     @Nullable String buildName,
                                     @NotNull Collection<DataNode<BuildScriptClasspathData>> buildClasspathNodes,
                                     @NotNull Map<String, ModuleData> existingProjectsPaths,
                                     @NotNull DefaultProjectResolverContext buildSrcResolverCtx,
                                     @NotNull Function<ProjectConnection, DataNode<ProjectData>> projectConnectionDataNodeFunction) {
    final String projectPath = buildSrcResolverCtx.getProjectPath();
    File projectPathFile = new File(projectPath);
    if (!projectPathFile.isDirectory()) {
      return;
    }

    if (ArrayUtil.isEmpty(projectPathFile.list((dir, name) -> !name.equals(".gradle") && !name.equals("build")))) {
      return;
    }

    if (buildSrcResolverCtx.isPreviewMode()) {
      ModuleData buildSrcModuleData =
        new ModuleData(":buildSrc", GradleConstants.SYSTEM_ID, getDefaultModuleTypeId(), "buildSrc", projectPath, projectPath);
      resultProjectDataNode.createChild(ProjectKeys.MODULE, buildSrcModuleData);
      return;
    }

    final DataNode<ProjectData> buildSrcProjectDataNode = myProjectResolver.getHelper().execute(
      projectPath, buildSrcResolverCtx.getSettings(), projectConnectionDataNodeFunction);

    if (buildSrcProjectDataNode == null) return;

    Map<String, DataNode<? extends ModuleData>> buildSrcModules = ContainerUtil.newHashMap();

    boolean modulePerSourceSet = buildSrcResolverCtx.isResolveModulePerSourceSet();
    DataNode<? extends ModuleData> buildSrcModuleNode = null;
    for (DataNode<ModuleData> moduleNode : getChildren(buildSrcProjectDataNode, ProjectKeys.MODULE)) {
      final ModuleData moduleData = moduleNode.getData();
      buildSrcModules.put(moduleData.getId(), moduleNode);
      boolean isBuildSrcModule = "buildSrc".equals(moduleData.getExternalName());

      if (isBuildSrcModule && !modulePerSourceSet) {
        buildSrcModuleNode = moduleNode;
      }
      if (modulePerSourceSet) {
        for (DataNode<GradleSourceSetData> sourceSetNode : getChildren(moduleNode, GradleSourceSetData.KEY)) {
          buildSrcModules.put(sourceSetNode.getData().getId(), sourceSetNode);
          if (isBuildSrcModule && buildSrcModuleNode == null && sourceSetNode.getData().getExternalName().endsWith(":main")) {
            buildSrcModuleNode = sourceSetNode;
          }
        }
      }

      if (!existingProjectsPaths.containsKey(moduleData.getLinkedExternalProjectPath())) {
        resultProjectDataNode.addChild(moduleNode);
        if (!buildSrcResolverCtx.isUseQualifiedModuleNames()) {
          // adjust ide module group
          if (moduleData.getIdeModuleGroup() != null) {
            String[] moduleGroup = ArrayUtil.prepend(
              StringUtil.isNotEmpty(buildName) ? buildName : resultProjectDataNode.getData().getInternalName(),
              moduleData.getIdeModuleGroup());
            moduleData.setIdeModuleGroup(moduleGroup);

            for (DataNode<GradleSourceSetData> sourceSetNode : getChildren(moduleNode, GradleSourceSetData.KEY)) {
              sourceSetNode.getData().setIdeModuleGroup(moduleGroup);
            }
          }
        }
      }
    }
    if (buildSrcModuleNode != null) {
      Set<String> buildSrcRuntimeSourcesPaths = ContainerUtil.newHashSet();
      Set<String> buildSrcRuntimeClassesPaths = ContainerUtil.newHashSet();

      addSourcePaths(buildSrcRuntimeSourcesPaths, buildSrcModuleNode);

      for (DataNode<?> child : buildSrcModuleNode.getChildren()) {
        Object childData = child.getData();
        if (childData instanceof ModuleDependencyData && ((ModuleDependencyData)childData).getScope().isForProductionRuntime()) {
          DataNode<? extends ModuleData> depModuleNode = buildSrcModules.get(((ModuleDependencyData)childData).getTarget().getId());
          if (depModuleNode != null) {
            addSourcePaths(buildSrcRuntimeSourcesPaths, depModuleNode);
          }
        }
        else if (childData instanceof LibraryDependencyData) {
          LibraryDependencyData dependencyData = (LibraryDependencyData)childData;
          // exclude generated gradle-api jar the gradle api classes/sources handled separately by BuildClasspathModuleGradleDataService
          if (dependencyData.getExternalName().startsWith("gradle-api-")) {
            continue;
          }
          LibraryData libraryData = dependencyData.getTarget();
          buildSrcRuntimeSourcesPaths.addAll(libraryData.getPaths(LibraryPathType.SOURCE));
          buildSrcRuntimeClassesPaths.addAll(libraryData.getPaths(LibraryPathType.BINARY));
        }
      }

      if (!buildSrcRuntimeSourcesPaths.isEmpty() || !buildSrcRuntimeClassesPaths.isEmpty()) {
        buildClasspathNodes.forEach(classpathNode -> {
          BuildScriptClasspathData data = classpathNode.getData();
          List<BuildScriptClasspathData.ClasspathEntry> classpathEntries = ContainerUtil.newArrayList();
          classpathEntries.addAll(data.getClasspathEntries());
          classpathEntries.add(new BuildScriptClasspathData.ClasspathEntry(
            new HashSet<>(buildSrcRuntimeClassesPaths),
            new HashSet<>(buildSrcRuntimeSourcesPaths),
            Collections.emptySet()
          ));
          BuildScriptClasspathData buildScriptClasspathData = new BuildScriptClasspathData(GradleConstants.SYSTEM_ID, classpathEntries);
          buildScriptClasspathData.setGradleHomeDir(data.getGradleHomeDir());

          DataNode<?> parent = classpathNode.getParent();
          assert parent != null;
          parent.createChild(BuildScriptClasspathData.KEY, buildScriptClasspathData);
          classpathNode.clear(true);
        });
      }
    }
  }

  private static void addSourcePaths(Set<String> paths, DataNode<? extends ModuleData> moduleNode) {
    getChildren(moduleNode, ProjectKeys.CONTENT_ROOT)
      .stream()
      .flatMap(contentNode -> contentNode.getData().getPaths(ExternalSystemSourceType.SOURCE).stream())
      .map(ContentRootData.SourceRoot::getPath)
      .forEach(paths::add);
  }

  @NotNull
  private static String getBuildSrcGroup(String buildPath, String buildName, ModuleData moduleData) {
    String buildSrcGroup = null;
    if (moduleData != null) {
      buildSrcGroup = moduleData.getGroup();
    }
    if (StringUtil.isEmpty(buildSrcGroup)) {
      buildSrcGroup = buildName;
    }
    if (StringUtil.isEmpty(buildSrcGroup)) {
      buildSrcGroup = new File(buildPath).getName();
    }
    return buildSrcGroup;
  }
}
