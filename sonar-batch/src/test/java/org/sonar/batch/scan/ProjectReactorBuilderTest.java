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

import com.google.common.collect.Maps;
import org.apache.commons.io.IOUtils;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.sonar.api.batch.bootstrap.ProjectDefinition;
import org.sonar.api.batch.bootstrap.ProjectReactor;
import org.sonar.batch.bootstrap.TaskProperties;
import org.sonar.test.TestUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.fest.assertions.Assertions.assertThat;

public class ProjectReactorBuilderTest {

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Test
  public void shouldDefineSimpleProject() throws IOException {
    ProjectDefinition projectDefinition = loadProjectDefinition("simple-project");

    assertThat(projectDefinition.getKey()).isEqualTo("com.foo.project");
    assertThat(projectDefinition.getName()).isEqualTo("Foo Project");
    assertThat(projectDefinition.getVersion()).isEqualTo("1.0-SNAPSHOT");
    assertThat(projectDefinition.getDescription()).isEqualTo("Description of Foo Project");
    assertThat(projectDefinition.getSourceDirs()).contains("sources");
    assertThat(projectDefinition.getLibraries()).contains(TestUtils.getResource(this.getClass(), "simple-project/libs/lib2.txt").getAbsolutePath(),
      TestUtils.getResource(this.getClass(), "simple-project/libs/lib2.txt").getAbsolutePath());
  }

  @Test
  public void shouldFailIfUnexistingSourceDirectory() throws IOException {
    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("The folder 'unexisting-source-dir' does not exist for 'com.foo.project' (base directory = "
      + TestUtils.getResource(this.getClass(), "simple-project-with-unexisting-source-dir") + ")");

    loadProjectDefinition("simple-project-with-unexisting-source-dir");
  }

  @Test
  public void fail_if_sources_not_set() throws IOException {
    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("You must define the following mandatory properties for 'com.foo.project': sonar.sources");
    loadProjectDefinition("simple-project-with-missing-source-dir");
  }

  @Test
  public void shouldNotFailIfBlankSourceDirectory() throws IOException {
    loadProjectDefinition("simple-project-with-blank-source-dir");
  }

  @Test
  public void shouldDefineMultiModuleProjectWithDefinitionsAllInRootProject() throws IOException {
    ProjectDefinition rootProject = loadProjectDefinition("multi-module-definitions-all-in-root");

    // CHECK ROOT
    assertThat(rootProject.getKey()).isEqualTo("com.foo.project");
    assertThat(rootProject.getName()).isEqualTo("Foo Project");
    assertThat(rootProject.getVersion()).isEqualTo("1.0-SNAPSHOT");
    assertThat(rootProject.getDescription()).isEqualTo("Description of Foo Project");
    // root project must not contain some properties - even if they are defined in the root properties file
    assertThat(rootProject.getSourceDirs().contains("sources")).isFalse();
    assertThat(rootProject.getTestDirs().contains("tests")).isFalse();
    assertThat(rootProject.getBinaries().contains("target/classes")).isFalse();
    // and module properties must have been cleaned
    assertThat(rootProject.getProperties().getProperty("module1.sonar.projectKey")).isNull();
    assertThat(rootProject.getProperties().getProperty("module2.sonar.projectKey")).isNull();
    // Check baseDir and workDir
    assertThat(rootProject.getBaseDir().getCanonicalFile())
      .isEqualTo(TestUtils.getResource(this.getClass(), "multi-module-definitions-all-in-root"));
    assertThat(rootProject.getWorkDir().getCanonicalFile())
      .isEqualTo(new File(TestUtils.getResource(this.getClass(), "multi-module-definitions-all-in-root"), ".sonar"));

    // CHECK MODULES
    List<ProjectDefinition> modules = rootProject.getSubProjects();
    assertThat(modules.size()).isEqualTo(2);

    // Module 1
    ProjectDefinition module1 = modules.get(0);
    assertThat(module1.getBaseDir().getCanonicalFile()).isEqualTo(TestUtils.getResource(this.getClass(), "multi-module-definitions-all-in-root/module1"));
    assertThat(module1.getKey()).isEqualTo("com.foo.project:module1");
    assertThat(module1.getName()).isEqualTo("module1");
    assertThat(module1.getVersion()).isEqualTo("1.0-SNAPSHOT");
    // Description should not be inherited from parent if not set
    assertThat(module1.getDescription()).isNull();
    assertThat(module1.getSourceDirs()).contains("sources");
    assertThat(module1.getTestDirs()).contains("tests");
    assertThat(module1.getBinaries()).contains("target/classes");
    // and module properties must have been cleaned
    assertThat(module1.getProperties().getProperty("module1.sonar.projectKey")).isNull();
    assertThat(module1.getProperties().getProperty("module2.sonar.projectKey")).isNull();
    // Check baseDir and workDir
    assertThat(module1.getBaseDir().getCanonicalFile())
      .isEqualTo(TestUtils.getResource(this.getClass(), "multi-module-definitions-all-in-root/module1"));
    assertThat(module1.getWorkDir().getCanonicalFile())
      .isEqualTo(new File(TestUtils.getResource(this.getClass(), "multi-module-definitions-all-in-root"), ".sonar/com.foo.project_module1"));

    // Module 2
    ProjectDefinition module2 = modules.get(1);
    assertThat(module2.getBaseDir().getCanonicalFile()).isEqualTo(TestUtils.getResource(this.getClass(), "multi-module-definitions-all-in-root/module2"));
    assertThat(module2.getKey()).isEqualTo("com.foo.project:com.foo.project.module2");
    assertThat(module2.getName()).isEqualTo("Foo Module 2");
    assertThat(module2.getVersion()).isEqualTo("1.0-SNAPSHOT");
    assertThat(module2.getDescription()).isEqualTo("Description of Module 2");
    assertThat(module2.getSourceDirs()).contains("src");
    assertThat(module2.getTestDirs()).contains("tests");
    assertThat(module2.getBinaries()).contains("target/classes");
    // and module properties must have been cleaned
    assertThat(module2.getProperties().getProperty("module1.sonar.projectKey")).isNull();
    assertThat(module2.getProperties().getProperty("module2.sonar.projectKey")).isNull();
    // Check baseDir and workDir
    assertThat(module2.getBaseDir().getCanonicalFile())
      .isEqualTo(TestUtils.getResource(this.getClass(), "multi-module-definitions-all-in-root/module2"));
    assertThat(module2.getWorkDir().getCanonicalFile())
      .isEqualTo(new File(TestUtils.getResource(this.getClass(), "multi-module-definitions-all-in-root"), ".sonar/com.foo.project_com.foo.project.module2"));
  }

  // SONAR-4876
  @Test
  public void shouldDefineMultiModuleProjectWithModuleKey() throws IOException {
    ProjectDefinition rootProject = loadProjectDefinition("multi-module-definitions-moduleKey");

    // CHECK ROOT
    // module properties must have been cleaned
    assertThat(rootProject.getProperties().getProperty("module1.sonar.moduleKey")).isNull();
    assertThat(rootProject.getProperties().getProperty("module2.sonar.moduleKey")).isNull();

    // CHECK MODULES
    List<ProjectDefinition> modules = rootProject.getSubProjects();
    assertThat(modules.size()).isEqualTo(2);

    // Module 2
    ProjectDefinition module2 = modules.get(1);
    assertThat(module2.getKey()).isEqualTo("com.foo.project.module2");
  }

  // SONARPLUGINS-2421
  @Test
  public void shouldDefineMultiLanguageProjectWithDefinitionsAllInRootProject() throws IOException {
    ProjectDefinition rootProject = loadProjectDefinition("multi-language-definitions-all-in-root");

    // CHECK ROOT
    assertThat(rootProject.getKey()).isEqualTo("example");
    assertThat(rootProject.getName()).isEqualTo("Example");
    assertThat(rootProject.getVersion()).isEqualTo("1.0");

    // CHECK MODULES
    List<ProjectDefinition> modules = rootProject.getSubProjects();
    assertThat(modules.size()).isEqualTo(2);

    // Module 1
    ProjectDefinition module1 = modules.get(0);
    assertThat(module1.getBaseDir().getCanonicalFile()).isEqualTo(TestUtils.getResource(this.getClass(), "multi-language-definitions-all-in-root"));
    assertThat(module1.getSourceDirs()).contains("src/main/java");
    // and module properties must have been cleaned
    assertThat(module1.getWorkDir().getCanonicalFile())
      .isEqualTo(new File(TestUtils.getResource(this.getClass(), "multi-language-definitions-all-in-root"), ".sonar/example_java-module"));

    // Module 2
    ProjectDefinition module2 = modules.get(1);
    assertThat(module2.getBaseDir().getCanonicalFile()).isEqualTo(TestUtils.getResource(this.getClass(), "multi-language-definitions-all-in-root"));
    assertThat(module2.getSourceDirs()).contains("src/main/groovy");
    // and module properties must have been cleaned
    assertThat(module2.getWorkDir().getCanonicalFile())
      .isEqualTo(new File(TestUtils.getResource(this.getClass(), "multi-language-definitions-all-in-root"), ".sonar/example_groovy-module"));
  }

  @Test
  public void shouldDefineMultiModuleProjectWithBaseDir() throws IOException {
    ProjectDefinition rootProject = loadProjectDefinition("multi-module-with-basedir");
    List<ProjectDefinition> modules = rootProject.getSubProjects();
    assertThat(modules.size()).isEqualTo(1);
    assertThat(modules.get(0).getKey()).isEqualTo("com.foo.project:com.foo.project.module1");
  }

  @Test
  public void shouldFailIfUnexistingModuleBaseDir() throws IOException {
    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("The base directory of the module 'module1' does not exist: "
      + TestUtils.getResource(this.getClass(), "multi-module-with-unexisting-basedir").getAbsolutePath() + File.separator + "module1");

    loadProjectDefinition("multi-module-with-unexisting-basedir");
  }

  @Test
  public void shouldFailIfUnexistingSourceFolderInheritedInMultimodule() throws IOException {
    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("The folder 'unexisting-source-dir' does not exist for 'com.foo.project:module1' (base directory = "
      + TestUtils.getResource(this.getClass(), "multi-module-with-unexisting-source-dir").getAbsolutePath() + File.separator + "module1)");

    loadProjectDefinition("multi-module-with-unexisting-source-dir");
  }

  @Test
  public void shouldNotFailIfUnexistingTestBinLibFolderInheritedInMultimodule() throws IOException {
    loadProjectDefinition("multi-module-with-unexisting-test-bin-lib-dir");
  }

  @Test
  public void shouldFailIfExplicitUnexistingTestFolder() throws IOException {
    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("The folder 'tests' does not exist for 'com.foo.project' (base directory = "
      + TestUtils.getResource(this.getClass(), "simple-project-with-unexisting-test-dir").getAbsolutePath());

    loadProjectDefinition("simple-project-with-unexisting-test-dir");
  }

  @Test
  public void shouldFailIfExplicitUnexistingBinaryFolder() throws IOException {
    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("The folder 'bin' does not exist for 'com.foo.project' (base directory = "
      + TestUtils.getResource(this.getClass(), "simple-project-with-unexisting-binary").getAbsolutePath());

    loadProjectDefinition("simple-project-with-unexisting-binary");
  }

  @Test
  public void shouldFailIfExplicitUnmatchingLibFolder() throws IOException {
    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("No files nor directories matching 'libs/*.txt' in directory "
      + TestUtils.getResource(this.getClass(), "simple-project-with-unexisting-lib").getAbsolutePath());

    loadProjectDefinition("simple-project-with-unexisting-lib");
  }

  @Test
  public void shouldGetLibDirectory() throws IOException {
    ProjectDefinition def = loadProjectDefinition("simple-project-with-lib-dir");
    assertThat(def.getLibraries()).hasSize(1);
    File libDir = new File(def.getLibraries().get(0));
    assertThat(libDir).isDirectory().exists();
    assertThat(libDir.getName()).isEqualTo("lib");
  }

  @Test
  public void shouldFailIfExplicitUnexistingTestFolderOnModule() throws IOException {
    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("The folder 'tests' does not exist for 'module1' (base directory = "
      + TestUtils.getResource(this.getClass(), "multi-module-with-explicit-unexisting-test-dir").getAbsolutePath() + File.separator + "module1)");

    loadProjectDefinition("multi-module-with-explicit-unexisting-test-dir");
  }

  @Test
  public void shouldFailIfExplicitUnexistingBinaryFolderOnModule() throws IOException {
    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("The folder 'bin' does not exist for 'module1' (base directory = "
      + TestUtils.getResource(this.getClass(), "multi-module-with-explicit-unexisting-binary-dir").getAbsolutePath() + File.separator + "module1)");

    loadProjectDefinition("multi-module-with-explicit-unexisting-binary-dir");
  }

  @Test
  public void shouldFailIfExplicitUnmatchingLibFolderOnModule() throws IOException {
    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("No files nor directories matching 'lib/*.jar' in directory "
      + TestUtils.getResource(this.getClass(), "multi-module-with-explicit-unexisting-lib").getAbsolutePath() + File.separator + "module1");

    loadProjectDefinition("multi-module-with-explicit-unexisting-lib");
  }

  @Test
  public void modulePropertiesShouldNotLeak() throws IOException {
    ProjectDefinition projectDefinition = loadProjectDefinition("big-multi-module-definitions-all-in-root");

    assertThat(projectDefinition.getProperties().getProperty("module11.property")).isNull();
    ProjectDefinition module1 = null;
    ProjectDefinition module2 = null;
    for (ProjectDefinition prj : projectDefinition.getSubProjects()) {
      if (prj.getKey().equals("com.foo.project:module1")) {
        module1 = prj;
      } else if (prj.getKey().equals("com.foo.project:module2")) {
        module2 = prj;
      }
    }
    assertThat(module1.getProperties().getProperty("module11.property")).isNull();
    assertThat(module1.getProperties().getProperty("property")).isNull();
    assertThat(module2.getProperties().getProperty("module11.property")).isNull();
    assertThat(module2.getProperties().getProperty("property")).isNull();

    ProjectDefinition module11 = null;
    ProjectDefinition module12 = null;
    for (ProjectDefinition prj : module1.getSubProjects()) {
      if (prj.getKey().equals("com.foo.project:module1:module11")) {
        module11 = prj;
      } else if (prj.getKey().equals("com.foo.project:module1:module12")) {
        module12 = prj;
      }
    }
    assertThat(module11.getProperties().getProperty("module11.property")).isNull();
    assertThat(module11.getProperties().getProperty("property")).isEqualTo("My module11 property");
    assertThat(module12.getProperties().getProperty("module11.property")).isNull();
    assertThat(module12.getProperties().getProperty("property")).isNull();
  }

  @Test
  public void shouldFailIfMandatoryPropertiesAreNotPresent() {
    Properties props = new Properties();
    props.setProperty("foo1", "bla");
    props.setProperty("foo4", "bla");

    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("You must define the following mandatory properties for 'Unknown': foo2, foo3");

    ProjectReactorBuilder.checkMandatoryProperties(props, new String[] {"foo1", "foo2", "foo3"});
  }

  @Test
  public void shouldFailIfMandatoryPropertiesAreNotPresentButWithProjectKey() {
    Properties props = new Properties();
    props.setProperty("foo1", "bla");
    props.setProperty("sonar.projectKey", "my-project");

    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("You must define the following mandatory properties for 'my-project': foo2, foo3");

    ProjectReactorBuilder.checkMandatoryProperties(props, new String[] {"foo1", "foo2", "foo3"});
  }

  @Test
  public void shouldNotFailIfMandatoryPropertiesArePresent() {
    Properties props = new Properties();
    props.setProperty("foo1", "bla");
    props.setProperty("foo4", "bla");

    ProjectReactorBuilder.checkMandatoryProperties(props, new String[] {"foo1"});

    // No exception should be thrown
  }

  @Test
  public void shouldFilterFiles() throws Exception {
    File baseDir = TestUtils.getResource(this.getClass(), "shouldFilterFiles");
    assertThat(ProjectReactorBuilder.getLibraries(baseDir, "in*.txt")).hasSize(1);
    assertThat(ProjectReactorBuilder.getLibraries(baseDir, "*.txt")).hasSize(2);
    assertThat(ProjectReactorBuilder.getLibraries(baseDir.getParentFile(), "shouldFilterFiles/in*.txt")).hasSize(1);
    assertThat(ProjectReactorBuilder.getLibraries(baseDir.getParentFile(), "shouldFilterFiles/*.txt")).hasSize(2);
  }

  @Test
  public void shouldWorkWithAbsolutePath() throws Exception {
    File baseDir = new File("not-exists");
    String absolutePattern = TestUtils.getResource(this.getClass(), "shouldFilterFiles").getAbsolutePath() + "/in*.txt";
    assertThat(ProjectReactorBuilder.getLibraries(baseDir.getParentFile(), absolutePattern)).hasSize(1);
  }

  @Test
  public void shouldGetRelativeFile() {
    assertThat(ProjectReactorBuilder.resolvePath(TestUtils.getResource(this.getClass(), "/"), "shouldGetFile/foo.properties"))
      .isEqualTo(TestUtils.getResource(this.getClass(), "shouldGetFile/foo.properties"));
  }

  @Test
  public void shouldGetAbsoluteFile() {
    File file = TestUtils.getResource(this.getClass(), "shouldGetFile/foo.properties");

    assertThat(ProjectReactorBuilder.resolvePath(TestUtils.getResource(this.getClass(), "/"), file.getAbsolutePath()))
      .isEqualTo(file);
  }

  @Test
  public void shouldMergeParentProperties() {
    // Use a random value to avoid VM optimization that would create constant String and make s1 and s2 the same object
    int i = (int) Math.random() * 10;
    String s1 = "value" + i;
    String s2 = "value" + i;
    Properties parentProps = new Properties();
    parentProps.setProperty("toBeMergeProps", "fooParent");
    parentProps.setProperty("existingChildProp", "barParent");
    parentProps.setProperty("duplicatedProp", s1);
    parentProps.setProperty("sonar.projectDescription", "Desc from Parent");

    Properties childProps = new Properties();
    childProps.setProperty("existingChildProp", "barChild");
    childProps.setProperty("otherProp", "tutuChild");
    childProps.setProperty("duplicatedProp", s2);

    ProjectReactorBuilder.mergeParentProperties(childProps, parentProps);

    assertThat(childProps).hasSize(4);
    assertThat(childProps.getProperty("toBeMergeProps")).isEqualTo("fooParent");
    assertThat(childProps.getProperty("existingChildProp")).isEqualTo("barChild");
    assertThat(childProps.getProperty("otherProp")).isEqualTo("tutuChild");
    assertThat(childProps.getProperty("sonar.projectDescription")).isNull();
    assertThat(childProps.getProperty("duplicatedProp")).isSameAs(parentProps.getProperty("duplicatedProp"));
  }

  @Test
  public void shouldInitRootWorkDir() {
    ProjectReactorBuilder builder = new ProjectReactorBuilder(new TaskProperties(Maps.<String, String>newHashMap(), null));
    File baseDir = new File("target/tmp/baseDir");

    File workDir = builder.initRootProjectWorkDir(baseDir);

    assertThat(workDir).isEqualTo(new File(baseDir, ".sonar"));
  }

  @Test
  public void shouldInitWorkDirWithCustomRelativeFolder() {
    Map<String, String> props = Maps.<String, String>newHashMap();
    props.put("sonar.working.directory", ".foo");
    ProjectReactorBuilder builder = new ProjectReactorBuilder(new TaskProperties(props, null));
    File baseDir = new File("target/tmp/baseDir");

    File workDir = builder.initRootProjectWorkDir(baseDir);

    assertThat(workDir).isEqualTo(new File(baseDir, ".foo"));
  }

  @Test
  public void shouldInitRootWorkDirWithCustomAbsoluteFolder() {
    Map<String, String> props = Maps.<String, String>newHashMap();
    props.put("sonar.working.directory", new File("src").getAbsolutePath());
    ProjectReactorBuilder builder = new ProjectReactorBuilder(new TaskProperties(props, null));
    File baseDir = new File("target/tmp/baseDir");

    File workDir = builder.initRootProjectWorkDir(baseDir);

    assertThat(workDir).isEqualTo(new File("src").getAbsoluteFile());
  }

  @Test
  public void shouldFailIf2ModulesWithSameKey() {
    Properties props = new Properties();
    props.put("sonar.projectKey", "root");
    ProjectDefinition root = ProjectDefinition.create().setProperties(props);

    Properties props1 = new Properties();
    props1.put("sonar.projectKey", "mod1");
    root.addSubProject(ProjectDefinition.create().setProperties(props1));

    // Check uniqueness of a new module: OK
    Properties props2 = new Properties();
    props2.put("sonar.projectKey", "mod2");
    ProjectDefinition mod2 = ProjectDefinition.create().setProperties(props2);
    ProjectReactorBuilder.checkUniquenessOfChildKey(mod2, root);

    // Now, add it and check again
    root.addSubProject(mod2);

    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("Project 'root' can't have 2 modules with the following key: mod2");

    ProjectReactorBuilder.checkUniquenessOfChildKey(mod2, root);
  }

  @Test
  public void shouldSetModuleKeyIfNotPresent() {
    Properties props = new Properties();
    props.put("sonar.projectVersion", "1.0");

    // should be set
    ProjectReactorBuilder.setModuleKeyAndNameIfNotDefined(props, "foo", "parent");
    assertThat(props.getProperty("sonar.moduleKey")).isEqualTo("parent:foo");
    assertThat(props.getProperty("sonar.projectName")).isEqualTo("foo");

    // but not this 2nd time
    ProjectReactorBuilder.setModuleKeyAndNameIfNotDefined(props, "bar", "parent");
    assertThat(props.getProperty("sonar.moduleKey")).isEqualTo("parent:foo");
    assertThat(props.getProperty("sonar.projectName")).isEqualTo("foo");
  }

  @Test
  public void shouldFailToLoadPropertiesFile() throws Exception {
    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("Impossible to read the property file");

    ProjectReactorBuilder.toProperties(new File("foo.properties"));
  }

  private ProjectDefinition loadProjectDefinition(String projectFolder) throws IOException {
    Map<String, String> props = Maps.<String, String>newHashMap();
    Properties runnerProps = ProjectReactorBuilder.toProperties(TestUtils.getResource(this.getClass(), projectFolder + "/sonar-project.properties"));
    for (final String name : runnerProps.stringPropertyNames()) {
      props.put(name, runnerProps.getProperty(name));
    }
    props.put("sonar.projectBaseDir", TestUtils.getResource(this.getClass(), projectFolder).getAbsolutePath());
    TaskProperties bootstrapProps = new TaskProperties(props, null);
    ProjectReactor projectReactor = new ProjectReactorBuilder(bootstrapProps).execute();
    return projectReactor.getRoot();
  }

  public Map<String, String> toMap(Properties props) {
    Map<String, String> result = new HashMap<String, String>();
    for (Map.Entry<Object, Object> entry : props.entrySet()) {
      result.put(entry.getKey().toString(), entry.getValue().toString());
    }
    return result;
  }

  @Test
  public void shouldGetList() {
    Properties props = new Properties();

    props.put("prop", "  foo  ,  bar  , \n\ntoto,tutu");
    assertThat(ProjectReactorBuilder.getListFromProperty(props, "prop")).containsOnly("foo", "bar", "toto", "tutu");
  }

  @Test
  public void shouldGetListFromFile() throws IOException {
    String filePath = "shouldGetList/foo.properties";
    Properties props = loadPropsFromFile(filePath);

    assertThat(ProjectReactorBuilder.getListFromProperty(props, "prop")).containsOnly("foo", "bar", "toto", "tutu");
  }

  @Test
  public void shouldDefineProjectWithBuildDir() throws IOException {
    ProjectDefinition rootProject = loadProjectDefinition("simple-project-with-build-dir");
    File buildDir = rootProject.getBuildDir();
    assertThat(buildDir).isDirectory().exists();
    assertThat(new File(buildDir, "report.txt")).isFile().exists();
    assertThat(buildDir.getName()).isEqualTo("build");
  }

  private Properties loadPropsFromFile(String filePath) throws IOException {
    Properties props = new Properties();
    FileInputStream fileInputStream = null;
    try {
      fileInputStream = new FileInputStream(TestUtils.getResource(this.getClass(), filePath));
      props.load(fileInputStream);
    } finally {
      IOUtils.closeQuietly(fileInputStream);
    }
    return props;
  }

}
