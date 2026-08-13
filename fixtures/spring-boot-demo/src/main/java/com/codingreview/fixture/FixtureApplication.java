package com.codingreview.fixture;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** M0 测试夹具的启动入口，用于验证 Coding、Diff 和 Maven 流程。 */
@SpringBootApplication
public class FixtureApplication {

    public static void main(String[] args) {
        SpringApplication.run(FixtureApplication.class, args);
    }
}
