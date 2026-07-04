package com.example.springbootrag.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class GraphPropertiesTest {

    @Autowired GraphProperties props;

    @Test
    void defaultsAreLoaded() {
        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getEdges()).isEqualTo("structural");
        assertThat(props.getNeighborHops()).isEqualTo(1);
        assertThat(props.getCandidates()).isEqualTo(50);
    }
}
