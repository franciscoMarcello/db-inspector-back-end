package com.chico.dbinspector.config

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import javax.sql.DataSource

@Configuration
@ConditionalOnProperty(name = ["hana.datasource.url"])
class HanaDataSourceConfig {

    @Bean("primaryDataSourceProperties")
    @Primary
    @ConfigurationProperties("spring.datasource")
    fun primaryDataSourceProperties(): DataSourceProperties = DataSourceProperties()

    @Bean("primaryDataSource")
    @Primary
    fun primaryDataSource(@Qualifier("primaryDataSourceProperties") props: DataSourceProperties): DataSource =
        props.initializeDataSourceBuilder().build()

    @Bean("hanaDataSourceProperties")
    @ConfigurationProperties("hana.datasource")
    fun hanaDataSourceProperties(): DataSourceProperties = DataSourceProperties()

    @Bean("hanaDataSource")
    fun hanaDataSource(@Qualifier("hanaDataSourceProperties") props: DataSourceProperties): DataSource =
        props.initializeDataSourceBuilder().build()

    @Bean("hanaJdbcTemplate")
    fun hanaJdbcTemplate(@Qualifier("hanaDataSource") dataSource: DataSource): JdbcTemplate {
        val template = JdbcTemplate(dataSource)
        template.maxRows = 0
        template.fetchSize = 0
        return template
    }
}
