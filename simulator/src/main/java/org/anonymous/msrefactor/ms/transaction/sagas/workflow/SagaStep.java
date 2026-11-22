package org.anonymous.msrefactor.ms.transaction.sagas.workflow;

import org.anonymous.msrefactor.ms.coordination.FlowStep;
import org.anonymous.msrefactor.ms.coordination.Step;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

public class SagaStep extends Step {
    private Runnable compensationLogic;

    public SagaStep(String stepName, Runnable syncOperation) {
        super(stepName, syncOperation);
    }

    public SagaStep(String stepName, Runnable syncOperation, ArrayList<FlowStep> dependencies) {
        super(stepName, syncOperation, dependencies);
    }

    public void registerCompensation(Runnable compensationLogic, UnitOfWork unitOfWork) {
        this.compensationLogic = compensationLogic;
    }

    public Runnable getCompensation() {
        return this.compensationLogic;
    }

    @Override
    public CompletableFuture<Void> execute(UnitOfWork unitOfWork) {
        SagaUnitOfWork sagaUow = (SagaUnitOfWork) unitOfWork;
        sagaUow.setCurrentExecutingStep(getName());
        
        // Add to executedSteps BEFORE running, so if it crashes mid-execution,
        // any acquired semantic locks will still be reverted during abort.
        if (!sagaUow.getExecutedSteps().contains(getName())) {
            sagaUow.getExecutedSteps().add(getName());
        }
        
        getSyncOperation().run();
        
        // Only register compensation logic if the step completed successfully
        if (getCompensation() != null) {
            sagaUow.registerCompensation(getName(), getCompensation());
        }
        
        return CompletableFuture.completedFuture(null);
    }
}
