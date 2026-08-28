package com.starticket.venue;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    boolean existsByArea_Id(Long areaId);

    List<Seat> findByArea_IdIn(Collection<Long> areaIds);
}
