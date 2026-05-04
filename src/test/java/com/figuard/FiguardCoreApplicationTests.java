package com.figuard;

import com.figuard.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

class FiguardCoreApplicationTests extends IntegrationTestBase {

	@Test
	void contextLoads() {
		// Verifies that the full Spring application context starts without errors.
		// Uses IntegrationTestBase so Testcontainers provides the datasource.
	}

}
