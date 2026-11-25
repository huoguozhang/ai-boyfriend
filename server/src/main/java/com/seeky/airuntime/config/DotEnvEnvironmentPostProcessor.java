package com.seeky.airuntime.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertiesPropertySource;

/**
 * 用 UTF-8 加载 {@code ./.env}，替代 {@code spring.config.import: ... [.properties]}。
 *
 * <p>为什么不能用官方的 properties 导入：Spring 读 {@code .properties} 走的是 ISO-8859-1，
 * 而 {@code .env} 里存的是中文角色卡（"小满""插秧师"这些），读进来就是
 * {@code å°æ»¡} 这样的乱码，前端角色名直接变成问号。改编码、加转义都很别扭，
 * 不如自己读一遍：{@link #postProcessEnvironment} 里就是 {@code Files.newBufferedReader(UTF_8)}。
 *
 * <p>优先级：加在**最后**。这样真实环境变量仍然压过 {@code .env}（容器/CI 注入生效），
 * 而 {@code application.yml} 里的 {@code ${PERSONA_NAME:小满}} 这类占位符在解析时
 * 依然能从 {@code .env} 取到值（占位符查的是全部 PropertySource，不看顺序）。
 */
public class DotEnvEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String SOURCE_NAME = "dotenv";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getPropertySources().contains(SOURCE_NAME)) {
            return;
        }
        Path file = Paths.get(".env").toAbsolutePath();
        if (!Files.isReadable(file)) {
            // 这里只能 System.out：EnvironmentPostProcessor 阶段日志系统还没初始化
            System.out.println("[dotenv] 未找到 .env，使用 application.yml 默认值：" + file);
            return; // 没有 .env 就全靠默认值与真实环境变量
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException ex) {
            // 配置读不出来不该让服务起不来：所有项都有默认值，最多是"没配 Key"
            System.out.println("[dotenv] 读取失败：" + ex.getMessage());
            return;
        }
        environment.getPropertySources().addLast(new PropertiesPropertySource(SOURCE_NAME, properties));
        System.out.println("[dotenv] 已加载 " + file + "，共 " + properties.size() + " 项"
                + "（LLM_PROTOCOL=" + properties.getProperty("LLM_PROTOCOL", "<未设置>") + "）");
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
