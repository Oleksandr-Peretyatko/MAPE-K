package org.anonymous.msrefactor.ms.transaction.sagas.workflow;

import org.anonymous.msrefactor.ms.coordination.ExecutionPlan;
import org.anonymous.msrefactor.ms.coordination.FlowStep;
import org.anonymous.msrefactor.ms.coordination.Workflow;
import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWorkService;

import java.util.*;

public class SagaWorkflow extends Workflow {
    public SagaWorkflow(WorkflowFunctionality functionality, UnitOfWorkService unitOfWorkService, SagaUnitOfWork unitOfWork) {
        super(functionality, unitOfWorkService, unitOfWork);
    }

    @Override
    public ExecutionPlan planOrder(HashMap<FlowStep, ArrayList<FlowStep>> stepsWithDependencies) {
        ArrayList<FlowStep> orderedSteps = new ArrayList<>();
        HashMap<FlowStep, Integer> inDegree = new HashMap<>();
        Queue<FlowStep> readySteps = new LinkedList<>();

        // calcular quantas dependencias tem cada step
        for (HashMap.Entry<FlowStep, ArrayList<FlowStep>> entry: stepsWithDependencies.entrySet()) {
            inDegree.put(entry.getKey(), entry.getValue().size());
        }

        // os steps sem dependencias estao prontos para ser ordenados
        for (HashMap.Entry<FlowStep, Integer> entry: inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                readySteps.add(entry.getKey());
            }
        }

        while (!readySteps.isEmpty()) {
            FlowStep step = readySteps.poll();
            orderedSteps.add(step);

            for (HashMap.Entry<FlowStep, ArrayList<FlowStep>> entry: stepsWithDependencies.entrySet()) {
                if (!entry.getKey().equals(step) && entry.getValue().contains(step)) {
                    inDegree.put(entry.getKey(), inDegree.get(entry.getKey()) - 1); // se o passo ordenado for uma dependencia de outro reduzir o numero de dependencias desse step
                    if (inDegree.get(entry.getKey()) == 0) { // se deixou de ter dependencias esta pronto a ser ordenado
                        readySteps.add(entry.getKey());
                    }
                }
            }
        }

        if (orderedSteps.size() != stepsWithDependencies.size()) {
            throw new IllegalStateException("Cyclic dependency detected in steps");
        }

        return new ExecutionPlan(orderedSteps, stepsWithDependencies, this.getFunctionality());
    }

    @Override
    public void compensateUntilStep(String stepName, UnitOfWork unitOfWork) {
        if (!this.aborted) {
            throw new IllegalStateException("Cannot execute compensations because the workflow has not aborted.");
        }
        SagaUnitOfWork sagaUnitOfWork = (SagaUnitOfWork) unitOfWork;
        SagaUnitOfWorkService sagaService = (SagaUnitOfWorkService) unitOfWorkService;
        sagaService.abortUntilStep(sagaUnitOfWork, stepName);
    }

    @Override
    public void resumeCompensation(UnitOfWork unitOfWork) {
        if (!this.aborted) {
            throw new IllegalStateException("Cannot execute compensations because the workflow has not aborted.");
        }
        SagaUnitOfWork sagaUnitOfWork = (SagaUnitOfWork) unitOfWork;
        SagaUnitOfWorkService sagaService = (SagaUnitOfWorkService) unitOfWorkService;
        sagaService.abort(sagaUnitOfWork);
    }
}
