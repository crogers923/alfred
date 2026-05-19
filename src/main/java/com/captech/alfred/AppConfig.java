/*
 * Copyright 2018 CapTech Ventures, Inc.
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

package com.captech.alfred;

import com.captech.alfred.dataConnections.DataStoreService;
import com.captech.alfred.dataConnections.DataUserStoreService;
import com.captech.alfred.dataConnections.UsersProperties;
import com.captech.alfred.dataConnections.hadoop.HadoopDatastoreService;
import com.captech.alfred.dataConnections.hadoop.HadoopProperties;
import com.captech.alfred.dataConnections.hadoop.HadoopUsers;
import com.captech.alfred.dataConnections.textFiles.TextFileProperties;
import com.captech.alfred.template.TabularFile;
import com.captech.alfred.template.TechnicalFile;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

import java.io.IOException;
import java.net.URI;


@Configuration
@Profile("!local")
@ComponentScan(value = { "com.captech.alfred" })
@EnableConfigurationProperties({HadoopProperties.class, TextFileProperties.class, UsersProperties.class})
public class AppConfig {

    @Autowired
    HadoopProperties properties;

    @Value("${spring.hadoop.config.fs.defaultFS:}")
    private String defaultFileSystem;

    @Bean
    @Primary
    DataStoreService getHadoopConnector() {
        return new HadoopDatastoreService();
    }

    @Bean
    @Primary
    DataUserStoreService getUserHadoopConnector() {
        return new HadoopUsers();
    }

    @Bean
    @Primary
    TechnicalFile getFileType() {
        return new TabularFile();
    }

    @Bean
    org.apache.hadoop.conf.Configuration hadoopConfiguration() {
        org.apache.hadoop.conf.Configuration configuration = new org.apache.hadoop.conf.Configuration();
        if (defaultFileSystem != null && !defaultFileSystem.isEmpty()) {
            configuration.set("fs.defaultFS", defaultFileSystem);
        }
        return configuration;
    }

    @Bean
    FileSystem hadoopFileSystem(org.apache.hadoop.conf.Configuration hadoopConfiguration) throws IOException {
        if (defaultFileSystem != null && !defaultFileSystem.isEmpty()) {
            return FileSystem.get(URI.create(defaultFileSystem), hadoopConfiguration);
        }
        return FileSystem.get(hadoopConfiguration);
    }

    @Bean
    Path sampleDirectory() {
        return new Path(properties.getFullSampleDir());
    }

}
