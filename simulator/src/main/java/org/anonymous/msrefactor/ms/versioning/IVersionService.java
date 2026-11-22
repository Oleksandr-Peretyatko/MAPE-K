package org.anonymous.msrefactor.ms.versioning;

public interface IVersionService {

    Long getVersionNumber();

    Long getNextVersionNumber();

    Long incrementAndGetVersionNumber();

    void decrementVersionNumber();
}
