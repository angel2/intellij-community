/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.plugins.gradle.config;

import com.intellij.ProjectTopics;
import com.intellij.compiler.server.BuildManager;
import com.intellij.facet.Facet;
import com.intellij.facet.FacetManager;
import com.intellij.openapi.compiler.CompileContext;
import com.intellij.openapi.compiler.CompilerMessageCategory;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.externalSystem.model.ExternalFilter;
import com.intellij.openapi.externalSystem.model.ExternalProject;
import com.intellij.openapi.externalSystem.model.ExternalSourceDirectorySet;
import com.intellij.openapi.externalSystem.model.ExternalSourceSet;
import com.intellij.openapi.externalSystem.model.project.ExternalSystemSourceType;
import com.intellij.openapi.externalSystem.service.project.manage.ProjectDataManager;
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil;
import com.intellij.openapi.externalSystem.util.ExternalSystemConstants;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.ModuleAdapter;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectType;
import com.intellij.openapi.project.ProjectTypeService;
import com.intellij.openapi.roots.CompilerModuleExtension;
import com.intellij.openapi.util.JDOMUtil;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.util.ArrayUtil;
import com.intellij.util.Function;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.FactoryMap;
import com.intellij.util.xmlb.XmlSerializer;
import gnu.trove.THashMap;
import org.jdom.Document;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jps.gradle.model.impl.*;
import org.jetbrains.plugins.gradle.service.project.data.ExternalProjectDataService;
import org.jetbrains.plugins.gradle.util.GradleConstants;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author Vladislav.Soroka
 * @since 7/10/2014
 */
public class GradleResourceCompilerConfigurationGenerator {

  private static Logger LOG = Logger.getInstance(GradleResourceCompilerConfigurationGenerator.class);

  @NotNull
  private final Project myProject;
  @NotNull
  private final Map<String, Integer> myModulesConfigurationHash;
  private final ExternalProjectDataService myExternalProjectDataService;

  public GradleResourceCompilerConfigurationGenerator(@NotNull final Project project) {
    myProject = project;
    myModulesConfigurationHash = ContainerUtil.newConcurrentMap();
    myExternalProjectDataService =
      (ExternalProjectDataService)ServiceManager.getService(ProjectDataManager.class).getDataService(ExternalProjectDataService.KEY);
    assert myExternalProjectDataService != null;

    project.getMessageBus().connect(project).subscribe(ProjectTopics.MODULES, new ModuleAdapter() {
      public void moduleRemoved(Project project, Module module) {
        myModulesConfigurationHash.remove(module.getName());
      }

      @Override
      public void modulesRenamed(Project project, List<Module> modules, Function<Module, String> oldNameProvider) {
        for (Module module : modules) {
          moduleRemoved(project, module);
        }
      }
    });
  }

  public void generateBuildConfiguration(@NotNull final CompileContext context) {

    if (shouldBeBuiltByExternalSystem(myProject)) return;

    if (!hasGradleModules(context)) return;

    final BuildManager buildManager = BuildManager.getInstance();
    final File projectSystemDir = buildManager.getProjectSystemDirectory(myProject);
    if (projectSystemDir == null) return;

    final File gradleConfigFile = new File(projectSystemDir, GradleProjectConfiguration.CONFIGURATION_FILE_RELATIVE_PATH);

    final Map<String, GradleModuleResourceConfiguration> affectedGradleModuleConfigurations =
      generateAffectedGradleModulesConfiguration(context);

    if (affectedGradleModuleConfigurations.isEmpty()) return;

    boolean configurationUpdateRequired = context.isRebuild() || !gradleConfigFile.exists();

    final Map<String, Integer> affectedConfigurationHash = new THashMap<String, Integer>();
    for (Map.Entry<String, GradleModuleResourceConfiguration> entry : affectedGradleModuleConfigurations.entrySet()) {
      Integer moduleLastConfigurationHash = myModulesConfigurationHash.get(entry.getKey());
      int moduleCurrentConfigurationHash = entry.getValue().computeConfigurationHash();
      if (moduleLastConfigurationHash == null || moduleLastConfigurationHash.intValue() != moduleCurrentConfigurationHash) {
        configurationUpdateRequired = true;
      }
      affectedConfigurationHash.put(entry.getKey(), moduleCurrentConfigurationHash);
    }

    final GradleProjectConfiguration projectConfig = loadLastConfiguration(gradleConfigFile);

    // update with newly generated configuration
    projectConfig.moduleConfigurations.putAll(affectedGradleModuleConfigurations);

    final Document document = new Document(new Element("gradle-project-configuration"));
    XmlSerializer.serializeInto(projectConfig, document.getRootElement());
    final boolean finalConfigurationUpdateRequired = configurationUpdateRequired;
    buildManager.runCommand(new Runnable() {
      @Override
      public void run() {
        if (finalConfigurationUpdateRequired) {
          buildManager.clearState(myProject);
        }
        FileUtil.createIfDoesntExist(gradleConfigFile);
        try {
          JDOMUtil.writeDocument(document, gradleConfigFile, "\n");
          myModulesConfigurationHash.putAll(affectedConfigurationHash);
        }
        catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    });
  }

  @NotNull
  private GradleProjectConfiguration loadLastConfiguration(@NotNull File gradleConfigFile) {
    final GradleProjectConfiguration projectConfig = new GradleProjectConfiguration();
    if (gradleConfigFile.exists()) {
      try {
        final Document document = JDOMUtil.loadDocument(gradleConfigFile);
        XmlSerializer.deserializeInto(projectConfig, document.getRootElement());

        // filter orphan modules
        final Set<String> actualModules = myModulesConfigurationHash.keySet();
        for (Iterator<Map.Entry<String, GradleModuleResourceConfiguration>> iterator =
               projectConfig.moduleConfigurations.entrySet().iterator(); iterator.hasNext(); ) {
          Map.Entry<String, GradleModuleResourceConfiguration> configurationEntry = iterator.next();
          if (!actualModules.contains(configurationEntry.getKey())) {
            iterator.remove();
          }
        }
      }
      catch (Exception e) {
        LOG.info(e);
      }
    }

    return projectConfig;
  }

  @NotNull
  private Map<String, GradleModuleResourceConfiguration> generateAffectedGradleModulesConfiguration(@NotNull CompileContext context) {
    final Map<String, GradleModuleResourceConfiguration> affectedGradleModuleConfigurations = ContainerUtil.newTroveMap();

    //noinspection MismatchedQueryAndUpdateOfCollection
    final Map<String, ExternalProject> lazyExternalProjectMap = new FactoryMap<String, ExternalProject>() {
      @Nullable
      @Override
      protected ExternalProject create(String gradleProjectPath) {
        return myExternalProjectDataService.getRootExternalProject(GradleConstants.SYSTEM_ID, new File(gradleProjectPath));
      }
    };

    for (Module module : context.getCompileScope().getAffectedModules()) {
      if (!ExternalSystemApiUtil.isExternalSystemAwareModule(GradleConstants.SYSTEM_ID, module)) continue;

      if (shouldBeBuiltByExternalSystem(module)) continue;

      final String gradleProjectPath = module.getOptionValue(ExternalSystemConstants.ROOT_PROJECT_PATH_KEY);
      assert gradleProjectPath != null;
      final ExternalProject externalRootProject = lazyExternalProjectMap.get(gradleProjectPath);
      if (externalRootProject == null) {
        context.addMessage(CompilerMessageCategory.ERROR,
                           String.format("Unable to make the module: %s, related gradle configuration was not found. " +
                                         "Please, re-import the Gradle project and try again.",
                                         module.getName()), VfsUtilCore.pathToUrl(gradleProjectPath), -1, -1);
        continue;
      }

      ExternalProject externalProject = myExternalProjectDataService.findExternalProject(externalRootProject, module);
      if (externalProject == null) {
        LOG.warn("Unable to find config for module: " + module.getName());
        continue;
      }

      GradleModuleResourceConfiguration resourceConfig = new GradleModuleResourceConfiguration();
      resourceConfig.id = new ModuleVersion(externalProject.getGroup(), externalProject.getName(), externalProject.getVersion());
      resourceConfig.directory = FileUtil.toSystemIndependentName(externalProject.getProjectDir().getPath());

      final ExternalSourceSet mainSourcesSet = externalProject.getSourceSets().get("main");
      addResources(resourceConfig.resources, mainSourcesSet, ExternalSystemSourceType.RESOURCE);

      final ExternalSourceSet testSourcesSet = externalProject.getSourceSets().get("test");
      addResources(resourceConfig.testResources, testSourcesSet, ExternalSystemSourceType.TEST_RESOURCE);

      final CompilerModuleExtension compilerModuleExtension = CompilerModuleExtension.getInstance(module);
      if (compilerModuleExtension != null && compilerModuleExtension.isCompilerOutputPathInherited()) {
        String outputPath = VfsUtilCore.urlToPath(compilerModuleExtension.getCompilerOutputUrl());
        for (ResourceRootConfiguration resource : resourceConfig.resources) {
          resource.targetPath = outputPath;
        }

        String testOutputPath = VfsUtilCore.urlToPath(compilerModuleExtension.getCompilerOutputUrlForTests());
        for (ResourceRootConfiguration resource : resourceConfig.testResources) {
          resource.targetPath = testOutputPath;
        }
      }

      affectedGradleModuleConfigurations.put(module.getName(), resourceConfig);
    }

    return affectedGradleModuleConfigurations;
  }

  private static boolean shouldBeBuiltByExternalSystem(@NotNull Project project) {
    // skip resource compilation by IDE for Android projects
    // TODO [vlad] this check should be replaced when an option to make any gradle project with gradle be introduced.
    ProjectType projectType = ProjectTypeService.getProjectType(project);
    if (projectType != null && "Android".equals(projectType.getId())) return true;
    return false;
  }

  private static boolean shouldBeBuiltByExternalSystem(@NotNull Module module) {
    for (Facet facet : FacetManager.getInstance(module).getAllFacets()) {
      if (ArrayUtil.contains(facet.getName(), "Android", "Android-Gradle", "Java-Gradle")) return true;
    }
    return false;
  }

  private static boolean hasGradleModules(@NotNull CompileContext context) {
    for (Module module : context.getCompileScope().getAffectedModules()) {
      if (ExternalSystemApiUtil.isExternalSystemAwareModule(GradleConstants.SYSTEM_ID, module)) return true;
    }
    return false;
  }

  private static void addResources(@NotNull List<ResourceRootConfiguration> container,
                                   @Nullable ExternalSourceSet externalSourceSet,
                                   @NotNull ExternalSystemSourceType sourceType) {
    if (externalSourceSet == null) return;
    final ExternalSourceDirectorySet directorySet = externalSourceSet.getSources().get(sourceType);
    if (directorySet == null) return;

    for (File file : directorySet.getSrcDirs()) {
      final String dir = file.getPath();
      final ResourceRootConfiguration rootConfiguration = new ResourceRootConfiguration();
      rootConfiguration.directory = FileUtil.toSystemIndependentName(dir);
      final String target = directorySet.getOutputDir().getPath();
      rootConfiguration.targetPath = FileUtil.toSystemIndependentName(target);

      rootConfiguration.includes.clear();
      for (String include : directorySet.getIncludes()) {
        rootConfiguration.includes.add(include.trim());
      }
      rootConfiguration.excludes.clear();
      for (String exclude : directorySet.getExcludes()) {
        rootConfiguration.excludes.add(exclude.trim());
      }

      rootConfiguration.isFiltered = !directorySet.getFilters().isEmpty();
      rootConfiguration.filters.clear();
      for (ExternalFilter filter : directorySet.getFilters()) {
        final ResourceRootFilter resourceRootFilter = new ResourceRootFilter();
        resourceRootFilter.filterType = filter.getFilterType();
        resourceRootFilter.properties = filter.getPropertiesAsJsonMap();
        rootConfiguration.filters.add(resourceRootFilter);
      }

      container.add(rootConfiguration);
    }
  }
}
