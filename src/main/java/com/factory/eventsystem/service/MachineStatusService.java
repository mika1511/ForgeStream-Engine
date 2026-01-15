package com.factory.eventsystem.service;

import com.factory.eventsystem.model.MachineStatus;
import org.springframework.stereotype.Service;


@Service
public class MachineStatusService {

    private static final double Health_threshold=2.0;

    public MachineStatus evaluate(double avgDefectRate){
        if(avgDefectRate<Health_threshold) return MachineStatus.HEALTHY;
        else return MachineStatus.WARNING;
    }
}
