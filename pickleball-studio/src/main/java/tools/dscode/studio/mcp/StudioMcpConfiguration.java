package tools.dscode.studio.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import tools.dscode.studio.build.GradleBuildService;
import tools.dscode.studio.build.MavenBuildService;
import tools.dscode.studio.collaboration.StudioCollaborationService;
import tools.dscode.studio.gradle.GradleProjectModelService;
import tools.dscode.studio.gui.StudioDesktopSession;
import tools.dscode.studio.language.WorkspaceLanguageService;
import tools.dscode.studio.process.ManagedProcessService;
import tools.dscode.studio.process.WorkspaceProcessService;
import tools.dscode.studio.runtime.RuntimeBridgeService;
import tools.dscode.studio.workspace.WorkspaceConcurrencyService;
import tools.dscode.studio.workspace.WorkspaceFileService;
import tools.dscode.studio.workspace.WorkspaceInfo;
import tools.dscode.studio.workspace.WorkspaceService;

import java.nio.file.Path;

@SpringBootConfiguration
@EnableAutoConfiguration
public class StudioMcpConfiguration {
    @Bean WorkspaceService workspaceService() { return new WorkspaceService(); }

    @Bean
    WorkspaceInfo workspaceInfo(WorkspaceService service, @Value("${pickleball.studio.workspace}") String root) {
        return service.open(Path.of(root));
    }

    @Bean WorkspaceFileService workspaceFileService(WorkspaceInfo workspace) { return new WorkspaceFileService(workspace.root()); }
    @Bean WorkspaceConcurrencyService workspaceConcurrencyService(WorkspaceFileService files) { return new WorkspaceConcurrencyService(files); }
    @Bean StudioCollaborationService studioCollaborationService() { return new StudioCollaborationService(); }
    @Bean WorkspaceProcessService workspaceProcessService(WorkspaceInfo workspace) { return new WorkspaceProcessService(workspace); }
    @Bean WorkspaceLanguageService workspaceLanguageService(WorkspaceFileService files) { return new WorkspaceLanguageService(files); }
    @Bean(destroyMethod = "close") ManagedProcessService managedProcessService(WorkspaceProcessService processes) { return new ManagedProcessService(processes); }

    @Bean
    MavenBuildService mavenBuildService(WorkspaceInfo workspace, WorkspaceProcessService processes, ManagedProcessService managed) {
        return new MavenBuildService(workspace, processes, managed);
    }

    @Bean
    GradleBuildService gradleBuildService(WorkspaceInfo workspace, WorkspaceProcessService processes, ManagedProcessService managed) {
        return new GradleBuildService(workspace, processes, managed);
    }

    @Bean GradleProjectModelService gradleProjectModelService(WorkspaceInfo workspace) { return new GradleProjectModelService(workspace); }

    @Bean(destroyMethod = "close")
    RuntimeBridgeService runtimeBridgeService(WorkspaceInfo workspace, MavenBuildService maven, GradleBuildService gradle) {
        return new RuntimeBridgeService(workspace, maven, gradle);
    }

    @Bean
    StudioDesktopSession studioDesktopSession(
            WorkspaceInfo workspace,
            WorkspaceFileService files,
            WorkspaceConcurrencyService concurrentFiles,
            WorkspaceLanguageService language,
            ManagedProcessService processes,
            MavenBuildService maven,
            GradleBuildService gradle,
            RuntimeBridgeService runtimeBridge,
            StudioCollaborationService collaboration
    ) {
        return StudioDesktopSession.shared(
                workspace,
                files,
                concurrentFiles,
                language,
                processes,
                maven,
                gradle,
                runtimeBridge,
                collaboration
        );
    }

    @Bean
    StudioMcpTools studioMcpTools(
            WorkspaceInfo workspace, WorkspaceFileService files, WorkspaceProcessService workspaceProcesses,
            ManagedProcessService processes, MavenBuildService maven, GradleBuildService gradle,
            GradleProjectModelService gradleModels, WorkspaceLanguageService language, RuntimeBridgeService runtimeBridge
    ) {
        return new StudioMcpTools(workspace, files, workspaceProcesses, processes, maven, gradle, gradleModels, language, runtimeBridge);
    }

    @Bean RuntimeEvidenceMcpTools runtimeEvidenceMcpTools(RuntimeBridgeService bridge) { return new RuntimeEvidenceMcpTools(bridge); }
    @Bean RuntimeMappingMcpTools runtimeMappingMcpTools(RuntimeBridgeService bridge) { return new RuntimeMappingMcpTools(bridge); }
    @Bean RuntimeBrowserEvidenceMcpTools runtimeBrowserEvidenceMcpTools(RuntimeBridgeService bridge) { return new RuntimeBrowserEvidenceMcpTools(bridge); }
    @Bean RuntimeInvestigationMcpTools runtimeInvestigationMcpTools(RuntimeBridgeService bridge) { return new RuntimeInvestigationMcpTools(bridge); }

    @Bean
    StudioCollaborationMcpTools studioCollaborationMcpTools(
            StudioCollaborationService collaboration,
            WorkspaceConcurrencyService concurrentFiles,
            ManagedProcessService processes,
            RuntimeBridgeService runtimeBridge
    ) {
        return new StudioCollaborationMcpTools(
                collaboration,
                concurrentFiles,
                processes,
                runtimeBridge
        );
    }

    @Bean
    ToolCallbackProvider studioTools(
            StudioMcpTools studio,
            RuntimeEvidenceMcpTools evidence,
            RuntimeMappingMcpTools mappings,
            RuntimeBrowserEvidenceMcpTools browser,
            RuntimeInvestigationMcpTools investigation,
            StudioCollaborationMcpTools collaboration,
            StudioCollaborationService studioCollaborationService
    ) {
        ToolCallbackProvider methods = MethodToolCallbackProvider.builder()
                .toolObjects(studio, evidence, mappings, browser, investigation, collaboration)
                .build();
        return new StudioObservedToolCallbackProvider(methods, studioCollaborationService);
    }
}
