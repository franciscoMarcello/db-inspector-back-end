package com.chico.dbinspector

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class DbInspectorApplication

fun main(args: Array<String>) {
	runApplication<DbInspectorApplication>(*args)
}
