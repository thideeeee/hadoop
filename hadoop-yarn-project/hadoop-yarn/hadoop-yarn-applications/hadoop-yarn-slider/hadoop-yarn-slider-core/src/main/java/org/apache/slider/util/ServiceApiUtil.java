/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.slider.util;

import com.google.common.annotations.VisibleForTesting;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.slider.api.resource.Application;
import org.apache.slider.api.resource.Artifact;
import org.apache.slider.api.resource.Component;
import org.apache.slider.api.resource.ConfigFile;
import org.apache.slider.api.resource.Configuration;
import org.apache.slider.api.resource.Resource;
import org.apache.slider.common.tools.SliderUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ServiceApiUtil {
  private static final Logger log =
      LoggerFactory.getLogger(ServiceApiUtil.class);
  @VisibleForTesting
  public static void validateApplicationPayload(Application application,
      FileSystem fs) throws IOException {
    if (StringUtils.isEmpty(application.getName())) {
      throw new IllegalArgumentException(
          RestApiErrorMessages.ERROR_APPLICATION_NAME_INVALID);
    }
    if (!SliderUtils.isClusternameValid(application.getName())) {
      throw new IllegalArgumentException(
          RestApiErrorMessages.ERROR_APPLICATION_NAME_INVALID_FORMAT);
    }

    // If the application has no components do top-level checks
    if (!hasComponent(application)) {
      // artifact
      if (application.getArtifact() == null) {
        throw new IllegalArgumentException(
            RestApiErrorMessages.ERROR_ARTIFACT_INVALID);
      }
      if (StringUtils.isEmpty(application.getArtifact().getId())) {
        throw new IllegalArgumentException(
            RestApiErrorMessages.ERROR_ARTIFACT_ID_INVALID);
      }

      // If artifact is of type APPLICATION, add a slider specific property
      if (application.getArtifact().getType()
          == Artifact.TypeEnum.APPLICATION) {
        if (application.getConfiguration() == null) {
          application.setConfiguration(new Configuration());
        }
      }
      // resource
      validateApplicationResource(application.getResource(), null,
          application.getArtifact().getType());

      // container size
      if (application.getNumberOfContainers() == null
          || application.getNumberOfContainers() < 0) {
        throw new IllegalArgumentException(
            RestApiErrorMessages.ERROR_CONTAINERS_COUNT_INVALID + ": "
                + application.getNumberOfContainers());
      }
      validateConfigFile(application.getConfiguration().getFiles(), fs);
      // Since it is a simple app with no components, create a default component
      application.getComponents().add(createDefaultComponent(application));
    } else {
      // If the application has components, then run checks for each component.
      // Let global values take effect if component level values are not
      // provided.
      Artifact globalArtifact = application.getArtifact();
      Resource globalResource = application.getResource();
      Long globalNumberOfContainers = application.getNumberOfContainers();
      for (Component comp : application.getComponents()) {
        // artifact
        if (comp.getArtifact() == null) {
          comp.setArtifact(globalArtifact);
        }
        // If still null raise validation exception
        if (comp.getArtifact() == null) {
          throw new IllegalArgumentException(String
              .format(RestApiErrorMessages.ERROR_ARTIFACT_FOR_COMP_INVALID,
                  comp.getName()));
        }
        if (StringUtils.isEmpty(comp.getArtifact().getId())) {
          throw new IllegalArgumentException(String
              .format(RestApiErrorMessages.ERROR_ARTIFACT_ID_FOR_COMP_INVALID,
                  comp.getName()));
        }

        // If artifact is of type APPLICATION, add a slider specific property
        if (comp.getArtifact().getType() == Artifact.TypeEnum.APPLICATION) {
          if (comp.getConfiguration() == null) {
            comp.setConfiguration(new Configuration());
          }
          comp.setName(comp.getArtifact().getId());
        }

        // resource
        if (comp.getResource() == null) {
          comp.setResource(globalResource);
        }
        validateApplicationResource(comp.getResource(), comp,
            comp.getArtifact().getType());

        // container count
        if (comp.getNumberOfContainers() == null) {
          comp.setNumberOfContainers(globalNumberOfContainers);
        }
        if (comp.getNumberOfContainers() == null
            || comp.getNumberOfContainers() < 0) {
          throw new IllegalArgumentException(String.format(
              RestApiErrorMessages.ERROR_CONTAINERS_COUNT_FOR_COMP_INVALID
                  + ": " + comp.getNumberOfContainers(), comp.getName()));
        }
        validateConfigFile(comp.getConfiguration().getFiles(), fs);
      }
    }

    // Application lifetime if not specified, is set to unlimited lifetime
    if (application.getLifetime() == null) {
      application.setLifetime(RestApiConstants.DEFAULT_UNLIMITED_LIFETIME);
    }
  }

  // 1) Verify the src_file exists and non-empty for template
  // 2) dest_file is absolute path
  private static void validateConfigFile(List<ConfigFile> list, FileSystem fs)
      throws IOException {
    Set<String> destFileSet = new HashSet<>();

    for (ConfigFile file : list) {
      if (file.getType().equals(ConfigFile.TypeEnum.TEMPLATE) && StringUtils
          .isEmpty(file.getSrcFile())) {
        throw new IllegalArgumentException(
            "Src_file is empty for " + ConfigFile.TypeEnum.TEMPLATE);

      }
      if (!StringUtils.isEmpty(file.getSrcFile())) {
        Path p = new Path(file.getSrcFile());
        if (!fs.exists(p)) {
          throw new IllegalArgumentException(
              "Src_file does not exist for config file: " + file
                  .getSrcFile());
        }
      }

      if (StringUtils.isEmpty(file.getDestFile())) {
        throw new IllegalArgumentException("Dest_file is empty.");
      }
      // validate dest_file is absolute
      if (!Paths.get(file.getDestFile()).isAbsolute()) {
        throw new IllegalArgumentException(
            "Dest_file must be absolute path: " + file.getDestFile());
      }

      if (destFileSet.contains(file.getDestFile())) {
        throw new IllegalArgumentException(
            "Duplicated ConfigFile exists: " + file.getDestFile());
      }
      destFileSet.add(file.getDestFile());
    }
  }


  private static void validateApplicationResource(Resource resource,
      Component comp, Artifact.TypeEnum artifactType) {
    // Only apps/components of type APPLICATION can skip resource requirement
    if (resource == null && artifactType == Artifact.TypeEnum.APPLICATION) {
      return;
    }
    if (resource == null) {
      throw new IllegalArgumentException(
          comp == null ? RestApiErrorMessages.ERROR_RESOURCE_INVALID : String
              .format(RestApiErrorMessages.ERROR_RESOURCE_FOR_COMP_INVALID,
                  comp.getName()));
    }
    // One and only one of profile OR cpus & memory can be specified. Specifying
    // both raises validation error.
    if (StringUtils.isNotEmpty(resource.getProfile()) && (
        resource.getCpus() != null || StringUtils
            .isNotEmpty(resource.getMemory()))) {
      throw new IllegalArgumentException(comp == null ?
          RestApiErrorMessages.ERROR_RESOURCE_PROFILE_MULTIPLE_VALUES_NOT_SUPPORTED :
          String.format(
              RestApiErrorMessages.ERROR_RESOURCE_PROFILE_MULTIPLE_VALUES_FOR_COMP_NOT_SUPPORTED,
              comp.getName()));
    }
    // Currently resource profile is not supported yet, so we will raise
    // validation error if only resource profile is specified
    if (StringUtils.isNotEmpty(resource.getProfile())) {
      throw new IllegalArgumentException(
          RestApiErrorMessages.ERROR_RESOURCE_PROFILE_NOT_SUPPORTED_YET);
    }

    String memory = resource.getMemory();
    Integer cpus = resource.getCpus();
    if (StringUtils.isEmpty(memory)) {
      throw new IllegalArgumentException(
          comp == null ? RestApiErrorMessages.ERROR_RESOURCE_MEMORY_INVALID :
              String.format(
                  RestApiErrorMessages.ERROR_RESOURCE_MEMORY_FOR_COMP_INVALID,
                  comp.getName()));
    }
    if (cpus == null) {
      throw new IllegalArgumentException(
          comp == null ? RestApiErrorMessages.ERROR_RESOURCE_CPUS_INVALID :
              String.format(
                  RestApiErrorMessages.ERROR_RESOURCE_CPUS_FOR_COMP_INVALID,
                  comp.getName()));
    }
    if (cpus <= 0) {
      throw new IllegalArgumentException(comp == null ?
          RestApiErrorMessages.ERROR_RESOURCE_CPUS_INVALID_RANGE : String
          .format(
              RestApiErrorMessages.ERROR_RESOURCE_CPUS_FOR_COMP_INVALID_RANGE,
              comp.getName()));
    }
  }

  public static boolean hasComponent(Application application) {
    if (application.getComponents() == null || application.getComponents()
        .isEmpty()) {
      return false;
    }
    return true;
  }

  public static Component createDefaultComponent(Application app) {
    Component comp = new Component();
    comp.setName(RestApiConstants.DEFAULT_COMPONENT_NAME);
    comp.setArtifact(app.getArtifact());
    comp.setResource(app.getResource());
    comp.setNumberOfContainers(app.getNumberOfContainers());
    comp.setLaunchCommand(app.getLaunchCommand());
    return comp;
  }

  public static String $(String s) {
    return "${" + s +"}";
  }
}
