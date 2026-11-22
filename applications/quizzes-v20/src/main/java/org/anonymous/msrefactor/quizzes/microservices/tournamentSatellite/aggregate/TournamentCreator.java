package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToOne;
import org.anonymous.msrefactor.ms.aggregate.Aggregate;
import org.anonymous.msrefactor.ms.aggregate.Replica;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

@Entity
@Replica("User")
public class TournamentCreator {
    @Id
    @GeneratedValue
    private Long id;
    private Integer creatorAggregateId;
    private String creatorName;
    private String creatorUsername;
    private Long creatorVersion;
    private Aggregate.AggregateState creatorState;

    @OneToOne
    private TournamentSatellite tournamentSatellite;

    public TournamentCreator() {

    }

    public TournamentCreator(Integer creatorAggregateId, String creatorName, String creatorUsername,
            Long creatorVersion) {
        setCreatorAggregateId(creatorAggregateId);
        setCreatorName(creatorName);
        setCreatorUsername(creatorUsername);
        setCreatorVersion(creatorVersion);
    }

    public TournamentCreator(TournamentCreator other) {
        setCreatorAggregateId(other.getCreatorAggregateId());
        setCreatorName(other.getCreatorName());
        setCreatorUsername(other.getCreatorUsername());
        setCreatorVersion(other.getCreatorVersion());
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getId() {
        return id;
    }

    public Integer getCreatorAggregateId() {
        return creatorAggregateId;
    }

    public void setCreatorAggregateId(Integer id) {
        this.creatorAggregateId = id;
    }

    public String getCreatorName() {
        return creatorName;
    }

    public void setCreatorName(String creatorName) {
        this.creatorName = creatorName;
    }

    public String getCreatorUsername() {
        return creatorUsername;
    }

    public void setCreatorUsername(String creatorUsername) {
        this.creatorUsername = creatorUsername;
    }

    public Long getCreatorVersion() {
        return creatorVersion;
    }

    public void setCreatorVersion(Long creatorVersion) {
        this.creatorVersion = creatorVersion;
    }

    public Aggregate.AggregateState getCreatorState() {
        return creatorState;
    }

    public void setCreatorState(Aggregate.AggregateState creatorState) {
        this.creatorState = creatorState;
    }

    @JsonIgnore
    public TournamentSatellite getTournamentSatellite() {
        return tournamentSatellite;
    }

    public void setTournamentSatellite(TournamentSatellite tournamentSatellite) {
        this.tournamentSatellite = tournamentSatellite;
    }

    public UserDto buildDto() {
        UserDto userDto = new UserDto();
        userDto.setAggregateId(getCreatorAggregateId());
        userDto.setVersion(getCreatorVersion());
        userDto.setName(getCreatorName());
        userDto.setUsername(getCreatorUsername());

        return userDto;
    }
}
