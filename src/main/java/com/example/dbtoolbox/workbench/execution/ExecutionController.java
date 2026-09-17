package com.example.dbtoolbox.workbench.execution;

import com.example.dbtoolbox.common.ApiResponse;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ExecutionController {
    private final SessionService sessions;
    private final ExecutionService executions;
    public ExecutionController(SessionService sessions,ExecutionService executions){this.sessions=sessions;this.executions=executions;}
    public static class SessionRequest{public String connectionId,schema,catalog;}
    public static class TransactionRequest{public String action;public boolean autoCommit;}
    @PostMapping("/sessions")public ApiResponse<SessionService.Session> create(@RequestBody SessionRequest request){return ApiResponse.ok(sessions.create(request.connectionId,request.schema,request.catalog));}
    @GetMapping("/sessions/{id}")public ApiResponse<SessionService.Session> session(@PathVariable String id){return ApiResponse.ok(sessions.get(id));}
    @DeleteMapping("/sessions/{id}")public ApiResponse<Void> close(@PathVariable String id){sessions.close(id);return ApiResponse.ok(null);}
    @PostMapping("/sessions/{id}/recover")public ApiResponse<SessionService.Session> recover(@PathVariable String id){return ApiResponse.ok(sessions.recover(id));}
    @PostMapping("/sessions/{id}/transaction")public ApiResponse<SessionService.Session> transaction(@PathVariable String id,@RequestBody TransactionRequest request){return ApiResponse.ok(sessions.transaction(id,request.action,request.autoCommit));}
    @PostMapping("/executions/prepare")public ApiResponse<ExecutionService.Plan> prepare(@RequestBody ExecutionRequest request){return ApiResponse.ok(executions.prepare(request));}
    @PostMapping("/executions")public ApiResponse<ExecutionRecord> submit(@RequestBody ExecutionRequest request){return ApiResponse.ok(executions.submit(request));}
    @GetMapping("/executions/{id}")public ApiResponse<ExecutionRecord> get(@PathVariable String id){return ApiResponse.ok(executions.get(id));}
    @DeleteMapping("/executions/{id}")public ApiResponse<Void> delete(@PathVariable String id){executions.delete(id);return ApiResponse.ok(null);}
    @PostMapping("/executions/{id}/cancel")public ApiResponse<ExecutionRecord> cancel(@PathVariable String id){return ApiResponse.ok(executions.cancel(id));}
    @GetMapping("/executions/{id}/results/{index}")public ApiResponse<ExecutionRecord.Result> result(@PathVariable String id,@PathVariable int index){
        List<ExecutionRecord.Result> results=new ArrayList<ExecutionRecord.Result>();for(ExecutionRecord.UnitResult u:executions.get(id).statements)results.addAll(u.results);
        if(index<0||index>=results.size())throw new com.example.dbtoolbox.common.AppException("结果序号不存在");return ApiResponse.ok(results.get(index));
    }
}
