package tools.dscode.studio.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import tools.dscode.studio.build.MavenBuildService;
import tools.dscode.studio.process.ManagedProcessService;
import tools.dscode.studio.process.WorkspaceProcessService;
import tools.dscode.studio.workspace.WorkspaceFileService;
import tools.dscode.studio.workspace.WorkspaceInfo;
import tools.dscode.studio.workspace.WorkspaceService;

import java.nio.file.Path;

@SpringBootConfiguration
@EnableAutoConfiguration
public class StudioMcpConfiguration {

    @Bean
    WorkspaceService workspaceService() {
        return new WorkspaceService();
    }

    @Bean
    WorkspaceInfo workspaceInfo(
            WorkspaceService workspaceService,
            @Value("${pickleball.studio.workspace}") String workspaceRoot
    ) {
        return workspaceService.open(Path.of(workspaceRoot));
    }

    @Bean
    WorkspaceFileService workspaceFileService(WorkspaceInfo workspaceInfo) {
        return new WorkspaceFileService(workspaceInfo.root());
    }

    @Bean
    WorkspaceProcessService workspaceProcessService(WorkspaceInfo workspaceInfo) {
        return new WorkspaceProcessService(workspaceInfo);
    }

    @Bean(destroyMethod = "close")
    ManagedProcessService managedProcessService(WorkspaceProcessService workspaceProcessService) {
        return new ManagedProcessService(workspaceProcessService);
    }

    @Bean
    MavenBuildService mavenBuildService(
            WorkspaceInfo workspaceInfo,
            WorkspaceProcessService workspaceProcessService,
            ManagedProcessService managedProcessService
    ) {
        return new MavenBuildService(workspaceInfo, workspaceProcessService, managedProcessService);
    }

    @Bean
    StudioMcpTools studioMcpTools(
            WorkspaceInfo workspaceInfo,
            WorkspaceFileService workspaceFileService,
            WorkspaceProcessService workspaceProcessService,
            ManagedProcessService managedProcessService,
            MavenBuildService mavenBuildService
    ) {
        return new StudioMcpTools(
                workspaceInfo,
                workspaceFileService,
                workspaceProcessService,
                managedProcessService,
                mavenBuildService
        );
    }

    @Bean
    ToolCallbackProvider studioTools(StudioMcpTools studioMcpTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(studioMcpTools)
                .build();
    }
}
