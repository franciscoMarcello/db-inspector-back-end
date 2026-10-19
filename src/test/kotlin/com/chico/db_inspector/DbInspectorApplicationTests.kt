package com.chico.dbinspector

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource

// Sincronizacao de perfis desligada: com ela, subir o contexto criaria no Keycloak real as roles dos
// perfis do banco local (conferencia do boot).
@SpringBootTest(classes = [DbInspectorApplication::class])
@TestPropertySource(properties = ["dbinspector.keycloak.admin.enabled=false"])
class DbInspectorApplicationTests {

	@Test
	fun contextLoads() {
	}

}
