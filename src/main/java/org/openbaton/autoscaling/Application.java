/*
 *
 *  *
 *  *  * Copyright (c) 2016 Open Baton (http://www.openbaton.org)
 *  *  *
 *  *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  *  * you may not use this file except in compliance with the License.
 *  *  * You may obtain a copy of the License at
 *  *  *
 *  *  *     http://www.apache.org/licenses/LICENSE-2.0
 *  *  *
 *  *  * Unless required by applicable law or agreed to in writing, software
 *  *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  * See the License for the specific language governing permissions and
 *  *  * limitations under the License.
 *  *
 *
 */

package org.openbaton.autoscaling;

import com.google.gson.JsonSyntaxException;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.commons.configuration.ConfigurationException;
import org.openbaton.autoscaling.configuration.AutoScalingProperties;
import org.openbaton.autoscaling.configuration.NfvoProperties;
import org.openbaton.autoscaling.configuration.PropertiesConfiguration;
import org.openbaton.autoscaling.configuration.SpringProperties;
import org.openbaton.autoscaling.core.management.ASBeanConfiguration;
import org.openbaton.autoscaling.core.management.ElasticityManagement;
import org.openbaton.autoscaling.utils.Utils;
import org.openbaton.catalogue.mano.record.NetworkServiceRecord;
import org.openbaton.catalogue.mano.record.Status;
import org.openbaton.catalogue.mano.record.VirtualNetworkFunctionRecord;
import org.openbaton.catalogue.nfvo.Action;
import org.openbaton.catalogue.nfvo.EndpointType;
import org.openbaton.catalogue.nfvo.EventEndpoint;
import org.openbaton.catalogue.security.Project;
import org.openbaton.catalogue.security.ServiceMetadata;
import org.openbaton.exceptions.NotFoundException;
import org.openbaton.exceptions.VimException;
import org.openbaton.plugin.mgmt.PluginStartup;
import org.openbaton.sdk.NFVORequestor;
import org.openbaton.sdk.NfvoRequestorBuilder;
import org.openbaton.sdk.api.exception.SDKException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.support.AnnotationConfigContextLoader;

/** Created by mpa on 27.10.15. */
@SpringBootApplication
@ComponentScan({"org.openbaton.autoscaling.api", "org.openbaton.autoscaling", "org.openbaton"})
@ContextConfiguration(
  loader = AnnotationConfigContextLoader.class,
  classes = {ASBeanConfiguration.class, PropertiesConfiguration.class}
)
public class Application implements CommandLineRunner, ApplicationListener<ContextClosedEvent> {

  protected static Logger log = LoggerFactory.getLogger(Application.class);

  @Autowired private ConfigurableApplicationContext context;

  private NFVORequestor nfvoRequestor;

  private List<String> subscriptionIds;

  @Autowired private AutoScalingProperties autoScalingProperties;

  @Autowired private SpringProperties springProperties;

  @Autowired private NfvoProperties nfvoProperties;

  private ElasticityManagement elasticityManagement;

  private void init()
      throws ClassNotFoundException, NotFoundException, SDKException, ConfigurationException {
    subscriptionIds = new ArrayList<>();
    //start all the plugins needed
    startPlugins();
    //waiting until the NFVO is available
    waitForNfvo();
    this.elasticityManagement = context.getBean(ElasticityManagement.class);
    if (autoScalingProperties.getService().getKey().isEmpty()) {
      log.warn("No service key provided. Trying to self register as new service...");
      if (nfvoProperties.getUsername().isEmpty() && nfvoProperties.getPassword().isEmpty()) {
        throw new NotFoundException(
            "Not found user and/or password to self register as a new service. Exiting....");
      }
      ServiceMetadata serviceMetadata = new ServiceMetadata();
      serviceMetadata.setName("autoscaling-engine");
      ArrayList<String> roles = new ArrayList<>();
      roles.add("*");

      NFVORequestor tmpNfvoRequestor =
          NfvoRequestorBuilder.create()
              .nfvoIp(nfvoProperties.getIp())
              .nfvoPort(Integer.parseInt(nfvoProperties.getPort()))
              .username(nfvoProperties.getUsername())
              .password(nfvoProperties.getPassword())
              .sslEnabled(nfvoProperties.getSsl().isEnabled())
              .version("1")
              .build();

      String serviceKey = tmpNfvoRequestor.getServiceAgent().create("autoscaling-engine", roles);
      log.info("Received service key: " + serviceKey);
      autoScalingProperties.updateProperty("ase.service.key", serviceKey);
    }
    try {
      this.nfvoRequestor =
          NfvoRequestorBuilder.create()
              .nfvoIp(nfvoProperties.getIp())
              .nfvoPort(Integer.parseInt(nfvoProperties.getPort()))
              .serviceName("autoscaling-engine")
              .serviceKey(autoScalingProperties.getService().getKey())
              .projectId("*")
              .sslEnabled(nfvoProperties.getSsl().isEnabled())
              .version("1")
              .build();
    } catch (SDKException e) {
      log.error(e.getMessage(), e);
      System.exit(1);
    }

    try {
      //List<Project> projectList = nfvoRequestor.getProjectAgent().findAll();
      //      for (Project project : projectList) {
      //        if (project.getName().equals("default")) {
      //          nfvoRequestor.setProjectId(project.getId());
      //        }
      //      }
      //      nfvoRequestor.setProjectId("*");
      subscriptionIds.add(subscribe(Action.INSTANTIATE_FINISH));
      subscriptionIds.add(subscribe(Action.RELEASE_RESOURCES_FINISH));
      subscriptionIds.add(subscribe(Action.ERROR));

      //for (ServiceMetadata serviceMetadata : nfvoRequestor.getServiceAgent().findAll()) {
      //  log.info(serviceMetadata.toString());
      //}

      List<EventEndpoint> eventEndpoints = nfvoRequestor.getEventAgent().findAll();
      if (eventEndpoints.size() == 3) {
        log.info("Subscribed successfully to all events...");
      } else {
        log.error(
            "Not subscribed successfully to events. Requried 3. Subscribed: "
                + eventEndpoints.size());
        log.warn(
            "Is that the IP were the autoscaling engine is reachable: "
                + autoScalingProperties.getServer().getIp()
                + "?");
        log.warn("Subscribed events: ");
        for (EventEndpoint event : nfvoRequestor.getEventAgent().findAll()) {
          log.info(event.toString());
        }
      }
    } catch (SDKException | IllegalStateException e) {
      log.error(e.getMessage());
      System.exit(1);
    } catch (JsonSyntaxException e) {
      log.error(
          "Credentials may be incorrect for talking to the NFVO. Please check 'nfvo.username' and 'nfvo.password' -> "
              + e.getMessage());
      System.exit(1);
    } catch (FileNotFoundException e) {
      e.printStackTrace();
      System.exit(1);
    }
    fetchNSRsFromNFVO();
  }

  private void exit() throws SDKException {
    try {
      unsubscribe();
    } catch (FileNotFoundException e) {
      e.printStackTrace();
    }
    destroyPlugins();
    List<NetworkServiceRecord> nsrs = new ArrayList<>();
    if (nfvoRequestor != null) {
      try {
        for (Project project : nfvoRequestor.getProjectAgent().findAll()) {
          nfvoRequestor.setProjectId(project.getId());
          nsrs.addAll(nfvoRequestor.getNetworkServiceRecordAgent().findAll());
        }
      } catch (SDKException e) {
        log.error(
            "Problem while fetching exisiting NSRs from the Orchestrator to start Autoscaling -> "
                + e.getMessage());
      } catch (JsonSyntaxException e) {
        log.error(
            "Credentials may be incorrect for talking to the NFVO. Please check 'nfvo.username' and 'nfvo.password' -> "
                + e.getMessage());
      }
      Set<Future<Boolean>> pendingTasks = new HashSet<>();
      for (NetworkServiceRecord nsr : nsrs) {
        for (VirtualNetworkFunctionRecord vnfr : nsr.getVnfr()) {
          pendingTasks.add(
              elasticityManagement.deactivate(nsr.getProjectId(), nsr.getId(), vnfr.getId()));
        }
      }
      for (Future<Boolean> pendingTask : pendingTasks) {
        try {
          pendingTask.get(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          if (log.isDebugEnabled()) {
            log.error(e.getMessage(), e);
          }
        } catch (ExecutionException e) {
          if (log.isDebugEnabled()) {
            log.error(e.getMessage(), e);
          }
        } catch (TimeoutException e) {
          if (log.isDebugEnabled()) {
            log.error(e.getMessage(), e);
          }
        }
      }
    } else {
      log.warn("NFVORequestor is not initialized. Cannot unsubscribe from events...");
    }
  }

  private String subscribe(Action action) throws SDKException, FileNotFoundException {
    log.debug("Subscribing to all NSR Events with Action " + action);
    EventEndpoint eventEndpoint = new EventEndpoint();
    eventEndpoint.setName("Subscription:" + action);
    eventEndpoint.setProjectId("*");
    eventEndpoint.setEndpoint(
        "http://"
            + autoScalingProperties.getServer().getIp()
            + ":"
            + autoScalingProperties.getServer().getPort()
            + "/elasticity-management/"
            + action);
    eventEndpoint.setEvent(action);
    eventEndpoint.setType(EndpointType.REST);
    return nfvoRequestor.getEventAgent().create(eventEndpoint).getId();
  }

  private void unsubscribe() throws SDKException, FileNotFoundException {
    for (String subscriptionId : subscriptionIds) {
      nfvoRequestor.getEventAgent().delete(subscriptionId);
    }
  }

  private void startPlugins() {
    if (autoScalingProperties.getPlugin().isStartup()) {
      File pluginFolder = new File(autoScalingProperties.getPlugin().getDir());
      if (pluginFolder.exists() && pluginFolder.isDirectory()) {
        try {
          PluginStartup.startPluginRecursive(
              autoScalingProperties.getPlugin().getDir(),
              true,
              autoScalingProperties.getRabbitmq().getBrokerIp(),
              String.valueOf(springProperties.getRabbitmq().getPort()),
              15,
              springProperties.getRabbitmq().getUsername(),
              springProperties.getRabbitmq().getPassword(),
              "/",
              autoScalingProperties.getRabbitmq().getManagement().getPort(),
              autoScalingProperties.getPlugin().getLog().getDir());
        } catch (Exception e) {
          log.error(e.getMessage(), e);
        }
      } else {
        log.warn(
            "Plugin folder '"
                + autoScalingProperties.getPlugin().getDir()
                + "' was not found. You may change the following configuration parameter 'autoscaling.plugin.path' to the path where the plugin(s) are located");
      }
    } else {
      log.warn(
          "Startup of plugins issued by the autoscaling-engine is disabled. Please consider to set 'autoscaling.plugin.startup' to 'true'");
    }
  }

  private void destroyPlugins() {
    PluginStartup.destroy();
  }

  private void waitForNfvo() {
    if (!Utils.isNfvoStarted(nfvoProperties.getIp(), nfvoProperties.getPort())) {
      log.error("After 150 sec the Nfvo is not started yet. Is there an error?");
      System.exit(1); // 1 stands for the error in running nfvo TODO define error codes (doing)
    }
  }

  private void fetchNSRsFromNFVO() {
    log.debug("Fetching previously deployed NSRs from NFVO to start the autoscaling for them.");
    List<NetworkServiceRecord> nsrs = new ArrayList<>();

    try {
      for (Project project : nfvoRequestor.getProjectAgent().findAll()) {
        nfvoRequestor.setProjectId(project.getId());
        nsrs.addAll(nfvoRequestor.getNetworkServiceRecordAgent().findAll());
      }
    } catch (SDKException e) {
      log.warn(
          "Problem while fetching exisiting NSRs from the Orchestrator to start Autoscaling. Elasticity for previously deployed NSRs will not start",
          e);
    }
    for (NetworkServiceRecord nsr : nsrs) {
      try {
        if (nsr.getStatus().ordinal() == Status.ACTIVE.ordinal()
            || nsr.getStatus().ordinal() == Status.SCALING.ordinal()) {
          log.debug("Adding previously deployed NSR with id: " + nsr.getId() + " to autoscaling");
          try {
            elasticityManagement.activate(nsr);
          } catch (VimException e) {
            log.error(e.getMessage(), e);
          } catch (SDKException e) {
            log.error(e.getMessage());
          }
        } else {
          log.warn(
              "Cannot add NSR with id: "
                  + nsr.getId()
                  + " to autoscaling because it is in state: "
                  + nsr.getStatus()
                  + " and not in state "
                  + Status.ACTIVE
                  + " or "
                  + Status.ERROR
                  + ". ");
        }
      } catch (NotFoundException e) {
        log.warn("Not found NSR with id: " + nsr.getId());
      }
    }
  }

  @Override
  public void onApplicationEvent(ContextClosedEvent event) {
    try {
      exit();
    } catch (SDKException e) {
      log.error(e.getMessage(), e);
    }
  }

  @Override
  public void run(String... args) throws Exception {
    init();
  }
}
