package org.anonymous.msrefactor.ms.transaction.causal.workflow;

import org.anonymous.msrefactor.ms.coordination.ExecutionPlan;
import org.anonymous.msrefactor.ms.coordination.FlowStep;
import org.anonymous.msrefactor.ms.coordination.Workflow;
import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.transaction.causal.unitOfWork.CausalUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWorkService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;

public class CausalWorkflow extends Workflow {
    public CausalWorkflow(WorkflowFunctionality functionality, UnitOfWorkService unitOfWorkService, CausalUnitOfWork unitOfWork) {
        super(functionality, unitOfWorkService, unitOfWork);
    }

    @Override
    public ExecutionPlan planOrder(HashMap<FlowStep, ArrayList<FlowStep>> stepsWithDependencies) {
        ArrayList<FlowStep> orderedSteps = new ArrayList<>();

        for (HashMap.Entry<FlowStep, ArrayList<FlowStep>> entry: stepsWithDependencies.entrySet()) {
            orderedSteps.add(entry.getKey());
        }

        return new ExecutionPlan(orderedSteps, stepsWithDependencies, this.getFunctionality());
    }

    @Override
    public CompletableFuture<Void> execute(UnitOfWork unitOfWork) {
        ExecutionPlan executionPlan = planOrder(this.stepsWithDependencies);

        // Initialize futures for steps with no dependencies
        for (FlowStep step: executionPlan.getPlan()) {
            step.execute(unitOfWork);
        }
        unitOfWorkService.commit(unitOfWork);
        return CompletableFuture.completedFuture(null);
    }
}
