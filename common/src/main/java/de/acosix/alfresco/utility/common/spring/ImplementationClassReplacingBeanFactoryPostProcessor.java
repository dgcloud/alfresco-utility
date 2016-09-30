/*
 * Copyright 2016 Acosix GmbH
 *
 * Licensed under the Eclipse Public License (EPL), Version 1.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the License at
 *
 * https://www.eclipse.org/legal/epl-v10.html
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package de.acosix.alfresco.utility.common.spring;

import java.util.Properties;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

/**
 * {@link BeanFactoryPostProcessor Bean factory post processor} to alter the implementation class of a bean definition without requiring an
 * override that may conflict with custom Spring configuration.
 *
 * @author Axel Faust, <a href="http://acosix.de">Acosix GmbH</a>
 */
public class ImplementationClassReplacingBeanFactoryPostProcessor implements BeanFactoryPostProcessor, BeanNameAware
{

    private static final Logger LOGGER = LoggerFactory.getLogger(ImplementationClassReplacingBeanFactoryPostProcessor.class);

    protected String beanName;

    protected String originalClassName;

    protected String replacementClassName;

    protected String targetBeanName;

    protected boolean active;

    protected String activePropertyKey;

    protected Properties propertiesSource;

    /**
     * {@inheritDoc}
     */
    @Override
    public void setBeanName(final String name)
    {
        this.beanName = name;
    }

    /**
     * @param originalClassName
     *            the originalClassName to set
     */
    public void setOriginalClassName(final String originalClassName)
    {
        this.originalClassName = originalClassName;
    }

    /**
     * @param replacementClassName
     *            the replacementClassName to set
     */
    public void setReplacementClassName(final String replacementClassName)
    {
        this.replacementClassName = replacementClassName;
    }

    /**
     * @param targetBeanName
     *            the targetBeanName to set
     */
    public void setTargetBeanName(final String targetBeanName)
    {
        this.targetBeanName = targetBeanName;
    }

    /**
     * @param active
     *            the active to set
     */
    public void setActive(final boolean active)
    {
        this.active = active;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void postProcessBeanFactory(final ConfigurableListableBeanFactory beanFactory) throws BeansException
    {
        if (this.active && this.targetBeanName != null && this.replacementClassName != null)
        {
            applyChange(beanName -> {
                return beanFactory.getBeanDefinition(beanName);
            });
        }
        else if (!this.active)
        {
            LOGGER.info("[{}] patch will not be applied as it has been marked as inactive", this.beanName);
        }
        else
        {
            LOGGER.warn("[{}] patch cannnot be applied as its configuration is incomplete", this.beanName);
        }
    }

    protected void applyChange(final Function<String, BeanDefinition> getBeanDefinition)
    {
        final BeanDefinition beanDefinition = getBeanDefinition.apply(this.targetBeanName);
        if (beanDefinition != null)
        {
            if (this.originalClassName == null || this.originalClassName.equals(beanDefinition.getBeanClassName()))
            {
                LOGGER.info("[{}] Patching implementation class Spring bean {} to {}", this.beanName, this.targetBeanName,
                        this.replacementClassName);
                beanDefinition.setBeanClassName(this.replacementClassName);
            }
            else
            {
                LOGGER.info("[{}] patch will not be applied - class of bean {} does not match expected implementation {}", this.beanName,
                        this.targetBeanName, this.originalClassName);
            }
        }
        else
        {
            LOGGER.info("[{}] patch cannot be applied - no bean with name {} has been defined", this.beanName, this.targetBeanName);
        }
    }

}