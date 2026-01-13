package com.factory.eventsystem;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean; // Correct import
import java.time.Clock;

@SpringBootTest
class EventsystemApplicationTests {

	// This must be INSIDE the class
	@MockitoBean
	private Clock clock;

	@Test
	void contextLoads() {
		// This test just checks if the Spring application starts correctly
	}
}