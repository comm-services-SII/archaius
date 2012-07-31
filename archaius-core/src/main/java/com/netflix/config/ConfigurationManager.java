/*
 *
 *  Copyright 2012 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */
package com.netflix.config;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.Collection;
import java.util.Properties;

import org.apache.commons.configuration.AbstractConfiguration;
import org.apache.commons.configuration.SystemConfiguration;
import org.apache.commons.configuration.event.ConfigurationListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.config.jmx.ConfigJMXManager;
import com.netflix.config.jmx.ConfigMBean;
import com.netflix.config.util.ConfigurationUtils;

/**
 * The configuration manager is a central place where it manages the system wide Configuration and
 * deployment context.
 * <p>
 * During initialization, this class will check system property "archaius.default.configuration.class"
 * and "archaius.default.configuration.factory". If the former is set, it will use the class name to instantiate 
 * it using its default no-arg constructor. If the later is set, it will call its static method getInstance().
 * In both cases, the returned Configuration object will be set as the system wide configuration.
 * 
 * @author awang
 *
 */
public class ConfigurationManager {
    
    static volatile AbstractConfiguration instance = null;
    static volatile boolean configurationInstalled = false;
    private static volatile ConfigMBean configMBean = null;
    private static final Logger logger = LoggerFactory.getLogger(ConfigurationManager.class);
    static volatile DeploymentContext context = null;
    
    static {
        try {
            String className = System.getProperty("archaius.default.configuration.class");
            if (className != null) {
                instance = (AbstractConfiguration) Class.forName(className).newInstance();
                configurationInstalled = true;
            } else {
                String factoryName = System.getProperty("archaius.default.configuration.factory");
                if (factoryName != null) {
                    Method m = Class.forName(factoryName).getDeclaredMethod("getInstance", new Class[]{});
                    m.setAccessible(true);
                    instance = (AbstractConfiguration) m.invoke(null, new Object[]{});
                    configurationInstalled = true;
                }
            }
            String contextClassName = System.getProperty("archaius.default.deploymentContext.class");
            if (contextClassName != null) {
                context = (DeploymentContext) Class.forName(className).newInstance();
            } else {
                String factoryName = System.getProperty("archaius.default.deploymentContext.factory");
                if (factoryName != null) {
                    Method m = Class.forName(factoryName).getDeclaredMethod("getInstance", new Class[]{});
                    m.setAccessible(true);
                    context = (DeploymentContext) m.invoke(null, new Object[]{});
                } else {
                    context = new ConfigurationBasedDeploymentContext();
                }
            }

        } catch (Exception e) {
            throw new RuntimeException("Error initializing configuration", e);
        }
    }
    
    /**
     * Install the system wide configuration with the ConfigurationManager. This will also install 
     * the configuration with the {@link DynamicPropertyFactory} by calling {@link DynamicPropertyFactory#initWithConfigurationSource(AbstractConfiguration)}.
     * This call can be made only once, otherwise IllegalStateException will be thrown.
     */
    public static synchronized void install(AbstractConfiguration config) throws IllegalStateException {
        if (!configurationInstalled) {
            if (instance != null) {
                removeDefaultConfiguration();
            }
            instance = config;
            if (DynamicPropertyFactory.getBackingConfigurationSource() != config) {                
                DynamicPropertyFactory.initWithConfigurationSource(config);
            }
            configurationInstalled = true;
            registerConfigBean();
        } else {
            throw new IllegalStateException("A non-default configuration is already installed");
        }
    }

    public static synchronized boolean isConfigurationInstalled() {
        return configurationInstalled;
    }
    
    /**
     * Get the current system wide configuration. If there has not been set, it will return a default
     * {@link ConcurrentCompositeConfiguration} which contains a SystemConfiguration from Apache Commons
     * Configuration and a {@link DynamicURLConfiguration}.
     */
    public static AbstractConfiguration getConfigInstance() {
        if (instance == null && !Boolean.getBoolean(DynamicPropertyFactory.DISABLE_DEFAULT_CONFIG)) {
            synchronized (ConfigurationManager.class) {
                if (instance == null) {
                    instance = new ConcurrentCompositeConfiguration();            
                    if (!Boolean.getBoolean(DynamicPropertyFactory.DISABLE_DEFAULT_SYS_CONFIG)) {
                        SystemConfiguration sysConfig = new SystemConfiguration();                
                        ((ConcurrentCompositeConfiguration) instance).addConfiguration(sysConfig, DynamicPropertyFactory.SYS_CONFIG_NAME);
                        try {
                            DynamicURLConfiguration defaultURLConfig = new DynamicURLConfiguration();
                            ((ConcurrentCompositeConfiguration) instance).addConfiguration(defaultURLConfig, DynamicPropertyFactory.URL_CONFIG_NAME);
                        } catch (Throwable e) {
                            logger.warn("Failed to create default dynamic configuration", e);
                        }
                    }
                    registerConfigBean();
                }
            }
        }
        return instance;
    }
    
    static void registerConfigBean() {
        if (Boolean.getBoolean(DynamicPropertyFactory.ENABLE_JMX)) {
            try {
                configMBean = ConfigJMXManager.registerConfigMbean(instance);
            } catch (Exception e) {
                logger.error("Unable to register with JMX", e);
            }
        }        
    }
    
    static void setDirect(AbstractConfiguration config) {
        ConfigurationManager.removeDefaultConfiguration();
        ConfigurationManager.instance = config;
        ConfigurationManager.configurationInstalled = true;
        ConfigurationManager.registerConfigBean();
    }
        
    /**
     * Load properties from resource file into the system wide configuration
     * @param path path of the resource
     * @throws IOException
     */
    public static void loadPropertiesFromResources(String path) 
            throws IOException {
        if (instance == null) {
            instance = getConfigInstance();
        }
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        URL url = loader.getResource(path);
        Properties props = new Properties();
        InputStream fin = url.openStream();
        props.load(fin);
        fin.close();
        if (instance instanceof AggregatedConfiguration) {
            String name = getConfigName(url);
            ConcurrentMapConfiguration config = new ConcurrentMapConfiguration();
            config.loadProperties(props);
            ((AggregatedConfiguration) instance).addConfiguration(config, name);
        } else {
            ConfigurationUtils.loadProperties(props, instance);
        }
    }
    
    /**
     * Load resource configName.properties first. Then load configName-deploymentEnvironment.properties
     * into the system wide configuration. For example, if configName is "application", and deployment environment
     * is "test", this API will first load "application.properties", then load "application-test.properties" to
     * override any property that also exist in "application.properties". 
     * 
     * @param configName prefix of the properties file name.
     * @throws IOException
     * @see DeploymentContext#getDeploymentEnvironment()
     */
    public static void loadCascadedPropertiesFromResources(String configName) throws IOException {
        String defaultConfigFileName = configName + ".properties";
        if (instance == null) {
            instance = getConfigInstance();
        }
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        URL url = loader.getResource(defaultConfigFileName);
        Properties props = new Properties();
        InputStream fin = url.openStream();
        props.load(fin);
        fin.close();
        String environment = getDeploymentContext().getDeploymentEnvironment();
        if (environment != null && environment.length() > 0) {
            String envConfigFileName = configName + "-" + environment + ".properties";
            url = loader.getResource(envConfigFileName);
            InputStream fin2 = url.openStream();
            props.load(fin2);
            fin2.close();
        }
        if (instance instanceof AggregatedConfiguration) {
            ConcurrentMapConfiguration config = new ConcurrentMapConfiguration();
            config.loadProperties(props);
            ((AggregatedConfiguration) instance).addConfiguration(config, configName);
        } else {
            ConfigurationUtils.loadProperties(props, instance);
        }
    }

    /**
     * Load properties from the specified configuration into system wide configuration
     */
    public static void loadPropertiesFromConfiguration(AbstractConfiguration config) {
        if (instance instanceof AggregatedConfiguration) {
            ((AggregatedConfiguration) instance).addConfiguration(config);
        } else {
            Properties props = ConfigurationUtils.getProperties(config);
            ConfigurationUtils.loadProperties(props, instance);
        }        
    }
    
    /**
     * Load the specified properties into system wide configuration
     */
    public static void loadProperties(Properties properties) {
        ConfigurationUtils.loadProperties(properties, instance);
    }
    
    public static void setDeploymentContext(DeploymentContext context) {
        ConfigurationManager.context = context;
    }
    
    public static DeploymentContext getDeploymentContext() {
        return context;
    }
    
    private static String getConfigName(URL propertyFile)
    {
        String name = propertyFile.toExternalForm();
        name = name.replace('\\', '/'); // Windows
        final String scheme = propertyFile.getProtocol().toLowerCase();
        if ("jar".equals(scheme) || "zip".equals(scheme)) {
            // Use the unqualified name of the jar file.
            final int bang = name.lastIndexOf("!");
            if (bang >= 0) {
                name = name.substring(0, bang);
            }
            final int slash = name.lastIndexOf("/");
            if (slash >= 0) {
                name = name.substring(slash + 1);
            }
        } else {
            // Use the URL of the enclosing directory.
            final int slash = name.lastIndexOf("/");
            if (slash >= 0) {
                name = name.substring(0, slash);
            }
        }
        return name;
    }
    
    static synchronized void removeDefaultConfiguration() {
        if (instance == null || configurationInstalled) {
            return;
        }
        ConcurrentCompositeConfiguration defaultConfig = (ConcurrentCompositeConfiguration) instance;
        // stop loading of the configuration
        DynamicURLConfiguration defaultFileConfig = (DynamicURLConfiguration) defaultConfig.getConfiguration(DynamicPropertyFactory.URL_CONFIG_NAME);
        if (defaultFileConfig != null) {
            defaultFileConfig.stopLoading();
        }
        Collection<ConfigurationListener> listeners = defaultConfig.getConfigurationListeners();
        
        // find the listener and remove it so that DynamicProperty will no longer receives 
        // callback from the default configuration source
        ConfigurationListener dynamicPropertyListener = null;
        for (ConfigurationListener l: listeners) {
            if (l instanceof ExpandedConfigurationListenerAdapter
                    && ((ExpandedConfigurationListenerAdapter) l).getListener() 
                    instanceof DynamicProperty.DynamicPropertyListener) {
                dynamicPropertyListener = l;
                break;                        
            }
        }
        if (dynamicPropertyListener != null) {
            defaultConfig.removeConfigurationListener(dynamicPropertyListener);
        }
        if (configMBean != null) {
            try {
                ConfigJMXManager.unRegisterConfigMBean(defaultConfig, configMBean);
            } catch (Exception e) {
                logger.error("Error unregistering with JMX", e);
            }
        }
        instance = null;        
    }    
}
