/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.batch.scan;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.filefilter.AndFileFilter;
import org.apache.commons.io.filefilter.FileFileFilter;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.CoreProperties;
import org.sonar.api.batch.bootstrap.ProjectDefinition;
import org.sonar.api.batch.bootstrap.ProjectReactor;
import org.sonar.batch.bootstrap.TaskProperties;

import javax.annotation.CheckForNull;
import javax.annotation.Nullable;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Class that creates a project definition based on a set of properties.
 */
public class ProjectReactorBuilder {

  private static final String INVALID_VALUE_OF_X_FOR_Y = "Invalid value of {0} for {1}";

  private static final Logger LOG = LoggerFactory.getLogger(ProjectReactorBuilder.class);

  /**
   * @since 4.1 but not yet exposed in {@link CoreProperties}
   */
  private static final String MODULE_KEY_PROPERTY = "sonar.moduleKey";

  protected static final String PROPERTY_PROJECT_BASEDIR = "sonar.projectBaseDir";
  private static final String PROPERTY_PROJECT_BUILDDIR = "sonar.projectBuildDir";
  private static final String PROPERTY_MODULES = "sonar.modules";

  /**
   * New properties, to be consistent with Sonar naming conventions
   *
   * @since 1.5
   */
  private static final String PROPERTY_SOURCES = "sonar.sources";
  private static final String PROPERTY_TESTS = "sonar.tests";
  private static final String PROPERTY_BINARIES = "sonar.binaries";
  private static final String PROPERTY_LIBRARIES = "sonar.libraries";

  /**
   * Array of all mandatory properties required for a project without child.
   */
  private static final String[] MANDATORY_PROPERTIES_FOR_SIMPLE_PROJECT = {
    PROPERTY_PROJECT_BASEDIR, CoreProperties.PROJECT_KEY_PROPERTY, CoreProperties.PROJECT_NAME_PROPERTY,
    CoreProperties.PROJECT_VERSION_PROPERTY, PROPERTY_SOURCES
  };

  /**
   * Array of all mandatory properties required for a project with children.
   */
  private static final String[] MANDATORY_PROPERTIES_FOR_MULTIMODULE_PROJECT = {PROPERTY_PROJECT_BASEDIR, CoreProperties.PROJECT_KEY_PROPERTY,
    CoreProperties.PROJECT_NAME_PROPERTY, CoreProperties.PROJECT_VERSION_PROPERTY};

  /**
   * Array of all mandatory properties required for a child project before its properties get merged with its parent ones.
   */
  protected static final String[] MANDATORY_PROPERTIES_FOR_CHILD = {MODULE_KEY_PROPERTY, CoreProperties.PROJECT_NAME_PROPERTY};

  /**
   * Properties that must not be passed from the parent project to its children.
   */
  private static final List<String> NON_HERITED_PROPERTIES_FOR_CHILD = Lists.newArrayList(PROPERTY_PROJECT_BASEDIR, CoreProperties.WORKING_DIRECTORY, PROPERTY_MODULES,
    CoreProperties.PROJECT_DESCRIPTION_PROPERTY);

  private TaskProperties taskProps;
  private File rootProjectWorkDir;

  public ProjectReactorBuilder(TaskProperties props) {
    this.taskProps = props;
  }

  public ProjectReactor execute() {
    Properties allProperties = new Properties();
    allProperties.putAll(taskProps.properties());
    SortedSet<String> moduleIds = new TreeSet<String>(new Comparator<String>() {
      @Override
      public int compare(String o1, String o2) {
        // Reverse string order
        return o2.compareTo(o1);
      }
    });
    collectModuleIds(null, allProperties, moduleIds);
    Map<String, Properties> propertiesByModuleId = extractPropertiesByModule(allProperties, new ArrayList<String>(moduleIds));
    ProjectDefinition rootProject = defineRootProject(propertiesByModuleId.get(""), null);
    rootProjectWorkDir = rootProject.getWorkDir();
    defineChildren(rootProject, propertiesByModuleId);
    cleanAndCheckProjectDefinitions(rootProject);
    // Optimization now that all properties have been read and stored in appropriate ProjectDefinition
    taskProps.properties().clear();
    return new ProjectReactor(rootProject);
  }

  private Map<String, Properties> extractPropertiesByModule(Properties allProperties, List<String> moduleIds) {
    Map<String, Properties> result = new HashMap<String, Properties>();
    for (String moduleId : moduleIds) {
      result.put(moduleId, new Properties());
    }
    // For root module
    result.put("", new Properties());

    for (Map.Entry<Object, Object> entry : allProperties.entrySet()) {
      String key = (String) entry.getKey();
      boolean found = false;
      for (String moduleId : moduleIds) {
        String propertyPrefix = moduleId + ".";
        int prefixLength = propertyPrefix.length();
        if (key.startsWith(propertyPrefix)) {
          result.get(moduleId).put(key.substring(prefixLength), entry.getValue());
          found = true;
          break;
        }
      }
      if (!found) {
        result.get("").put(key, entry.getValue());
      }
    }
    return result;
  }

  private void collectModuleIds(String currentModuleId, Properties allProperties, Set<String> moduleIds) {
    if (currentModuleId != null) {
      if (!moduleIds.add(currentModuleId)) {
        throw new IllegalStateException("Module ID '" + currentModuleId + "' is defined several times in the reactor");
      }
    }
    for (String moduleId : getListFromProperty(allProperties, (currentModuleId == null ? "" : (currentModuleId + ".")) + PROPERTY_MODULES)) {
      collectModuleIds(moduleId, allProperties, moduleIds);
    }
  }

  protected ProjectDefinition defineRootProject(Properties rootProperties, @Nullable ProjectDefinition parent) {
    if (rootProperties.containsKey(PROPERTY_MODULES)) {
      checkMandatoryProperties(rootProperties, MANDATORY_PROPERTIES_FOR_MULTIMODULE_PROJECT);
    } else {
      checkMandatoryProperties(rootProperties, MANDATORY_PROPERTIES_FOR_SIMPLE_PROJECT);
    }
    File baseDir = new File(rootProperties.getProperty(PROPERTY_PROJECT_BASEDIR));
    final String projectKey = rootProperties.getProperty(CoreProperties.PROJECT_KEY_PROPERTY);
    File workDir;
    if (parent == null) {
      validateDirectories(rootProperties, baseDir, projectKey);
      workDir = initRootProjectWorkDir(baseDir);
    } else {
      workDir = initModuleWorkDir(baseDir, rootProperties);
    }

    return ProjectDefinition.create().setProperties(rootProperties)
      .setBaseDir(baseDir)
      .setWorkDir(workDir)
      .setBuildDir(initModuleBuildDir(baseDir, rootProperties));
  }

  @VisibleForTesting
  protected File initRootProjectWorkDir(File baseDir) {
    String workDir = taskProps.property(CoreProperties.WORKING_DIRECTORY);
    if (StringUtils.isBlank(workDir)) {
      return new File(baseDir, CoreProperties.WORKING_DIRECTORY_DEFAULT_VALUE);
    }

    File customWorkDir = new File(workDir);
    if (customWorkDir.isAbsolute()) {
      return customWorkDir;
    }
    return new File(baseDir, customWorkDir.getPath());
  }

  @VisibleForTesting
  protected File initModuleWorkDir(File moduleBaseDir, Properties moduleProperties) {
    String workDir = moduleProperties.getProperty(CoreProperties.WORKING_DIRECTORY);
    if (StringUtils.isBlank(workDir)) {
      String cleanKey = StringUtils.deleteWhitespace(moduleProperties.getProperty(CoreProperties.PROJECT_KEY_PROPERTY));
      cleanKey = StringUtils.replace(cleanKey, ":", "_");
      return new File(rootProjectWorkDir, cleanKey);
    }

    File customWorkDir = new File(workDir);
    if (customWorkDir.isAbsolute()) {
      return customWorkDir;
    }
    return new File(moduleBaseDir, customWorkDir.getPath());
  }

  @CheckForNull
  private File initModuleBuildDir(File moduleBaseDir, Properties moduleProperties) {
    String buildDir = moduleProperties.getProperty(PROPERTY_PROJECT_BUILDDIR);
    if (StringUtils.isBlank(buildDir)) {
      return null;
    }

    File customBuildDir = new File(buildDir);
    if (customBuildDir.isAbsolute()) {
      return customBuildDir;
    }
    return new File(moduleBaseDir, customBuildDir.getPath());
  }

  private void defineChildren(ProjectDefinition parentProject, Map<String, Properties> propertiesByModuleId) {
    Properties parentProps = parentProject.getProperties();
    if (parentProps.containsKey(PROPERTY_MODULES)) {
      for (String moduleId : getListFromProperty(parentProps, PROPERTY_MODULES)) {
        Properties moduleProps = propertiesByModuleId.get(moduleId);
        ProjectDefinition childProject = loadChildProject(parentProject, moduleProps, moduleId);
        // check the uniqueness of the child key
        checkUniquenessOfChildKey(childProject, parentProject);
        // the child project may have children as well
        defineChildren(childProject, propertiesByModuleId);
        // and finally add this child project to its parent
        parentProject.addSubProject(childProject);
      }
    }
  }

  protected ProjectDefinition loadChildProject(ProjectDefinition parentProject, Properties moduleProps, String moduleId) {
    final File baseDir;
    if (moduleProps.containsKey(PROPERTY_PROJECT_BASEDIR)) {
      baseDir = resolvePath(parentProject.getBaseDir(), moduleProps.getProperty(PROPERTY_PROJECT_BASEDIR));
      setProjectBaseDir(baseDir, moduleProps, moduleId);
    } else {
      baseDir = new File(parentProject.getBaseDir(), moduleId);
      setProjectBaseDir(baseDir, moduleProps, moduleId);
    }

    setModuleKeyAndNameIfNotDefined(moduleProps, moduleId, parentProject.getKey());

    // and finish
    checkMandatoryProperties(moduleProps, MANDATORY_PROPERTIES_FOR_CHILD);
    validateDirectories(moduleProps, baseDir, moduleId);

    mergeParentProperties(moduleProps, parentProject.getProperties());

    return defineRootProject(moduleProps, parentProject);
  }

  @VisibleForTesting
  protected static Properties toProperties(File propertyFile) {
    Properties propsFromFile = new Properties();
    FileInputStream fileInputStream = null;
    try {
      fileInputStream = new FileInputStream(propertyFile);
      propsFromFile.load(fileInputStream);
    } catch (IOException e) {
      throw new IllegalStateException("Impossible to read the property file: " + propertyFile.getAbsolutePath(), e);
    } finally {
      IOUtils.closeQuietly(fileInputStream);
    }
    // Trim properties
    for (String propKey : propsFromFile.stringPropertyNames()) {
      propsFromFile.setProperty(propKey, StringUtils.trim(propsFromFile.getProperty(propKey)));
    }
    return propsFromFile;
  }

  @VisibleForTesting
  protected static void setModuleKeyAndNameIfNotDefined(Properties childProps, String moduleId, String parentKey) {
    if (!childProps.containsKey(MODULE_KEY_PROPERTY)) {
      if (!childProps.containsKey(CoreProperties.PROJECT_KEY_PROPERTY)) {
        childProps.put(MODULE_KEY_PROPERTY, parentKey + ":" + moduleId);
      } else {
        String childKey = childProps.getProperty(CoreProperties.PROJECT_KEY_PROPERTY);
        childProps.put(MODULE_KEY_PROPERTY, parentKey + ":" + childKey);
      }
    }
    if (!childProps.containsKey(CoreProperties.PROJECT_NAME_PROPERTY)) {
      childProps.put(CoreProperties.PROJECT_NAME_PROPERTY, moduleId);
    }
    // For backward compatibility with ProjectDefinition
    childProps.put(CoreProperties.PROJECT_KEY_PROPERTY, childProps.getProperty(MODULE_KEY_PROPERTY));
  }

  @VisibleForTesting
  protected static void checkUniquenessOfChildKey(ProjectDefinition childProject, ProjectDefinition parentProject) {
    for (ProjectDefinition definition : parentProject.getSubProjects()) {
      if (definition.getKey().equals(childProject.getKey())) {
        throw new IllegalStateException("Project '" + parentProject.getKey() + "' can't have 2 modules with the following key: " + childProject.getKey());
      }
    }
  }

  protected static void setProjectBaseDir(File baseDir, Properties childProps, String moduleId) {
    if (!baseDir.isDirectory()) {
      throw new IllegalStateException("The base directory of the module '" + moduleId + "' does not exist: " + baseDir.getAbsolutePath());
    }
    childProps.put(PROPERTY_PROJECT_BASEDIR, baseDir.getAbsolutePath());
  }

  @VisibleForTesting
  protected static void checkMandatoryProperties(Properties props, String[] mandatoryProps) {
    StringBuilder missing = new StringBuilder();
    for (String mandatoryProperty : mandatoryProps) {
      if (!props.containsKey(mandatoryProperty)) {
        if (missing.length() > 0) {
          missing.append(", ");
        }
        missing.append(mandatoryProperty);
      }
    }
    String moduleKey = StringUtils.defaultIfBlank(props.getProperty(MODULE_KEY_PROPERTY), props.getProperty(CoreProperties.PROJECT_KEY_PROPERTY));
    if (missing.length() != 0) {
      throw new IllegalStateException("You must define the following mandatory properties for '" + (moduleKey == null ? "Unknown" : moduleKey) + "': " + missing);
    }
  }

  protected static void validateDirectories(Properties props, File baseDir, String projectId) {
    if (!props.containsKey(PROPERTY_MODULES)) {
      // SONARPLUGINS-2285 Not an aggregator project so we can validate that paths are correct if defined

      // We need to resolve patterns that may have been used in "sonar.libraries"
      for (String pattern : getListFromProperty(props, PROPERTY_LIBRARIES)) {
        File[] files = getLibraries(baseDir, pattern);
        if (files == null || files.length == 0) {
          LOG.error(MessageFormat.format(INVALID_VALUE_OF_X_FOR_Y, PROPERTY_LIBRARIES, projectId));
          throw new IllegalStateException("No files nor directories matching '" + pattern + "' in directory " + baseDir);
        }
      }

      // Check sonar.tests
      String[] testPaths = getListFromProperty(props, PROPERTY_TESTS);
      checkExistenceOfPaths(projectId, baseDir, testPaths, PROPERTY_TESTS);

      // Check sonar.binaries
      String[] binDirs = getListFromProperty(props, PROPERTY_BINARIES);
      checkExistenceOfDirectories(projectId, baseDir, binDirs, PROPERTY_BINARIES);
    }
  }

  @VisibleForTesting
  protected static void cleanAndCheckProjectDefinitions(ProjectDefinition project) {
    if (project.getSubProjects().isEmpty()) {
      cleanAndCheckModuleProperties(project);
    } else {
      cleanAndCheckAggregatorProjectProperties(project);

      // clean modules properties as well
      for (ProjectDefinition module : project.getSubProjects()) {
        cleanAndCheckProjectDefinitions(module);
      }
    }
  }

  @VisibleForTesting
  protected static void cleanAndCheckModuleProperties(ProjectDefinition project) {
    Properties properties = project.getProperties();

    // We need to check the existence of source directories
    String[] sourcePaths = getListFromProperty(properties, PROPERTY_SOURCES);
    checkExistenceOfPaths(project.getKey(), project.getBaseDir(), sourcePaths, PROPERTY_SOURCES);

    // And we need to resolve patterns that may have been used in "sonar.libraries"
    List<String> libPaths = Lists.newArrayList();
    for (String pattern : getListFromProperty(properties, PROPERTY_LIBRARIES)) {
      for (File file : getLibraries(project.getBaseDir(), pattern)) {
        libPaths.add(file.getAbsolutePath());
      }
    }
    properties.remove(PROPERTY_LIBRARIES);
    properties.put(PROPERTY_LIBRARIES, StringUtils.join(libPaths, ","));
  }

  @VisibleForTesting
  protected static void cleanAndCheckAggregatorProjectProperties(ProjectDefinition project) {
    Properties properties = project.getProperties();

    // SONARPLUGINS-2295
    String[] sourceDirs = getListFromProperty(properties, PROPERTY_SOURCES);
    for (String path : sourceDirs) {
      File sourceFolder = resolvePath(project.getBaseDir(), path);
      if (sourceFolder.isDirectory()) {
        LOG.warn("/!\\ A multi-module project can't have source folders, so '{}' won't be used for the analysis. " +
          "If you want to analyse files of this folder, you should create another sub-module and move them inside it.",
          sourceFolder.toString());
      }
    }

    // "aggregator" project must not have the following properties:
    properties.remove(PROPERTY_SOURCES);
    properties.remove(PROPERTY_TESTS);
    properties.remove(PROPERTY_BINARIES);
    properties.remove(PROPERTY_LIBRARIES);
  }

  @VisibleForTesting
  protected static void mergeParentProperties(Properties childProps, Properties parentProps) {
    for (Map.Entry<Object, Object> entry : parentProps.entrySet()) {
      String key = (String) entry.getKey();
      if ((!childProps.containsKey(key) || childProps.get(key).equals(entry.getValue()))
        && !NON_HERITED_PROPERTIES_FOR_CHILD.contains(key)) {
        childProps.put(entry.getKey(), entry.getValue());
      }
    }
  }

  @VisibleForTesting
  protected static void checkExistenceOfDirectories(String moduleRef, File baseDir, String[] dirPaths, String propName) {
    for (String path : dirPaths) {
      File sourceFolder = resolvePath(baseDir, path);
      if (!sourceFolder.isDirectory()) {
        LOG.error(MessageFormat.format(INVALID_VALUE_OF_X_FOR_Y, propName, moduleRef));
        throw new IllegalStateException("The folder '" + path + "' does not exist for '" + moduleRef +
          "' (base directory = " + baseDir.getAbsolutePath() + ")");
      }
    }

  }

  @VisibleForTesting
  protected static void checkExistenceOfPaths(String moduleRef, File baseDir, String[] paths, String propName) {
    for (String path : paths) {
      File sourceFolder = resolvePath(baseDir, path);
      if (!sourceFolder.exists()) {
        LOG.error(MessageFormat.format(INVALID_VALUE_OF_X_FOR_Y, propName, moduleRef));
        throw new IllegalStateException("The folder '" + path + "' does not exist for '" + moduleRef +
          "' (base directory = " + baseDir.getAbsolutePath() + ")");
      }
    }

  }

  /**
   * Returns files matching specified pattern.
   */
  @VisibleForTesting
  protected static File[] getLibraries(File baseDir, String pattern) {
    final int i = Math.max(pattern.lastIndexOf('/'), pattern.lastIndexOf('\\'));
    final String dirPath, filePattern;
    if (i == -1) {
      dirPath = ".";
      filePattern = pattern;
    } else {
      dirPath = pattern.substring(0, i);
      filePattern = pattern.substring(i + 1);
    }
    List<IOFileFilter> filters = new ArrayList<IOFileFilter>();
    if (pattern.indexOf('*') >= 0) {
      filters.add(FileFileFilter.FILE);
    }
    filters.add(new WildcardFileFilter(filePattern));
    File dir = resolvePath(baseDir, dirPath);
    File[] files = dir.listFiles((FileFilter) new AndFileFilter(filters));
    if (files == null) {
      files = new File[0];
    }
    return files;
  }

  protected static File resolvePath(File baseDir, String path) {
    File file = new File(path);
    if (!file.isAbsolute()) {
      try {
        file = new File(baseDir, path).getCanonicalFile();
      } catch (IOException e) {
        throw new IllegalStateException("Unable to resolve path \"" + path + "\"", e);
      }
    }
    return file;
  }

  /**
   * Transforms a comma-separated list String property in to a array of trimmed strings.
   *
   * This works even if they are separated by whitespace characters (space char, EOL, ...)
   *
   */
  static String[] getListFromProperty(Properties properties, String key) {
    return StringUtils.stripAll(StringUtils.split(properties.getProperty(key, ""), ','));
  }

}
