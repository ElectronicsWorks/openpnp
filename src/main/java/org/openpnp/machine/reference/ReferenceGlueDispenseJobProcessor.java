/*
 * Copyright (C) 2011 Jason von Nieda <jason@vonnieda.org>
 *
 * This file is part of OpenPnP.
 *
 * OpenPnP is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * OpenPnP is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with OpenPnP. If not, see
 * <http://www.gnu.org/licenses/>.
 *
 * For more information about OpenPnP visit http://openpnp.org
 */

package org.openpnp.machine.reference;

import org.openpnp.machine.reference.ReferenceGlueDispenseJobProcessor.JobDispense.Status;
import org.openpnp.model.*;
import org.openpnp.spi.FiducialLocator;
import org.openpnp.spi.Head;
import org.openpnp.spi.Machine;
import org.openpnp.spi.PasteDispenser;
import org.openpnp.spi.base.AbstractPasteDispenseJobProcessor;
import org.openpnp.util.FiniteStateMachine;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.Utils2D;
import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.Root;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;


import java.util.*;

@Root
public class ReferenceGlueDispenseJobProcessor extends AbstractPasteDispenseJobProcessor {
    enum State {
        Uninitialized,
        PreFlight,
        FiducialCheck,
        Dispense,
        Cleanup,
        Stopped
    }

    enum Message {
        Initialize,
        Next,
        Complete,
        Abort,
        Skip,
        Reset
    }

    public static class JobDispense {
        public enum Status {
            Pending,
            Processing,
            Skipped,
            Complete
        }

        public final BoardLocation boardLocation;
        public final Placement placement;
        public Status status = Status.Pending;

        public JobDispense(BoardLocation boardLocation, Placement placement) {
            this.boardLocation = boardLocation;
            this.placement = placement;
        }

        public double getDistance()
        {
            Location zeroLocation=this.boardLocation.getLocation().derive((double) 0,(double) 0,(double) 0,(double) 0);

           return zeroLocation.getLinearDistanceTo((Utils2D.calculateBoardPlacementLocation(this.boardLocation,this.placement.getLocation())));
        }
    }

    private static final Logger logger = LoggerFactory.getLogger(ReferenceGlueDispenseJobProcessor.class);

    @Attribute(required = false)
    protected boolean parkWhenComplete = false;

    private FiniteStateMachine<State, Message> fsm = new FiniteStateMachine<>(State.Uninitialized);

    protected Job job;

    protected Machine machine;

    protected Head head;

    protected PasteDispenser pasteDispenser;

    protected List<JobDispense> jobDispenses = new ArrayList<>();

    protected Map<BoardLocation, Location> boardLocationFiducialOverrides = new HashMap<>();

    public ReferenceGlueDispenseJobProcessor() {
        fsm.add(State.Uninitialized, Message.Initialize, State.PreFlight, this::doInitialize);

        fsm.add(State.PreFlight, Message.Next, State.FiducialCheck, this::doPreFlight,
                Message.Next);
        fsm.add(State.PreFlight, Message.Abort, State.Cleanup, Message.Next);

        fsm.add(State.FiducialCheck, Message.Next, State.Dispense, this::doFiducialCheck, Message.Next);
        fsm.add(State.FiducialCheck, Message.Skip, State.Dispense, Message.Next);
        fsm.add(State.FiducialCheck, Message.Abort, State.Cleanup, Message.Next);

        fsm.add(State.Dispense, Message.Next, State.Cleanup, this::doDispense);
        fsm.add(State.Dispense, Message.Skip, State.Cleanup, this::doSkip, Message.Next);
        fsm.add(State.Dispense, Message.Abort, State.Cleanup, Message.Next);

        fsm.add(State.Cleanup, Message.Next, State.Stopped, this::doCleanup, Message.Reset);

        fsm.add(State.Stopped, Message.Reset, State.Uninitialized, this::doReset);
    }

    public synchronized void initialize(Job job) throws Exception {
        this.job = job;
        fsm.send(Message.Initialize);
    }

    public synchronized boolean next() throws Exception {
        if(fsm.getState() == State.Uninitialized)
            return false;

        fsm.send(Message.Next);

        if (fsm.getState() == State.Stopped) {
            /*
             * If we've reached the Stopped state the process is complete. We reset the FSM and
             * return false to indicate that we're finished.
             */
            fsm.send(Message.Reset);
            return false;
        }
        else if (fsm.getState() == State.Dispense && isJobComplete()) {
            /*
             * If we've reached the Dispense state and there are no more dispenses to work on the job
             * is complete. We send the Complete Message to start the cleanup process.
             */
            fsm.send(Message.Complete);
            return false;
        }

        return true;
    }

    public synchronized void abort() throws Exception {
        fsm.send(Message.Abort);
    }

    public synchronized void skip() throws Exception {
        fsm.send(Message.Skip);
    }


    public boolean canSkip() {
        return fsm.canSend(Message.Skip);
    }

    /**
     * Validate that there is a job set before allowing it to start.
     *
     * @throws Exception
     */
    protected void doInitialize() throws Exception {
        if (job == null) {
            throw new Exception("Can't initialize with a null Job.");
        }
    }

    /**
     * Create some internal shortcuts to various buried objects.
     *
     * Check for obvious setup errors in the job: Feeders are available and enabled, Placements all
     * have valid parts, Parts all have height values set, Each part has at least one compatible
     * nozzle tip.
     *
     * Populate the jobPlacements list with all the placements that we'll perform for the entire
     * job.
     *
     * Safe-Z the machine, discard any currently picked parts.
     *
     * @throws Exception
     */
    protected void doPreFlight() throws Exception {
        // Create some shortcuts for things that won't change during the run
        this.machine = Configuration.get().getMachine();
        this.head = this.machine.getDefaultHead();
        this.pasteDispenser = this.head.getDefaultPasteDispenser();
        this.jobDispenses.clear();
        this.boardLocationFiducialOverrides.clear();

        fireTextStatus("Checking job for setup errors.");

        for (BoardLocation boardLocation : job.getBoardLocations()) {
            // Only check enabled boards
            if (!boardLocation.isEnabled()) {
                continue;
            }
            for (Placement placement : boardLocation.getBoard().getPlacements()) {
                // Ignore placements that aren't set to be glued
                if (!placement.getGlue()) {
                    continue;
                }

                // Ignore placements that aren't on the side of the board we're processing.
                if (placement.getSide() != boardLocation.getSide()) {
                    continue;
                }

                JobDispense jobDispense = new JobDispense(boardLocation, placement);

                jobDispenses.add(jobDispense);
            }
        }

        // Do a very basic sort by distance to stop the machine going randomly round the PCB
        Collections.sort(jobDispenses, new Comparator<JobDispense>() {
            @Override
            public int compare(JobDispense c1, JobDispense c2) {
                return new Double(c1.getDistance()).compareTo(new Double(c2.getDistance()));
            }
        });

        // Everything looks good, so prepare the machine.
        fireTextStatus("Preparing machine.");

        // Safe Z the machine
        head.moveToSafeZ();
    }

    protected void doFiducialCheck() throws Exception {
        fireTextStatus("Performing fiducial checks.");

        FiducialLocator locator = Configuration.get().getMachine().getFiducialLocator();
        for (BoardLocation boardLocation : job.getBoardLocations()) {
            if (!boardLocation.isEnabled()) {
                continue;
            }
            if (!boardLocation.isCheckFiducials()) {
                continue;
            }
            Location location = locator.locateBoard(boardLocation);
            boardLocationFiducialOverrides.put(boardLocation, location);
            logger.debug("Fiducial check for {}", boardLocation);
        }
    }

    protected void doDispense() throws Exception {
        for(JobDispense jobDispense : jobDispenses) {
            /* if (jobDispense.stepComplete) {
                continue;
            }*/

            BoardLocation boardLocation = jobDispense.boardLocation;
            Placement placement= jobDispense.placement;

            // Check if there is a fiducial override for the board location and if so, use it.
            if (boardLocationFiducialOverrides.containsKey(boardLocation)) {
                BoardLocation boardLocation2 = new BoardLocation(boardLocation.getBoard());
                boardLocation2.setSide(boardLocation.getSide());
                boardLocation2.setLocation(boardLocationFiducialOverrides.get(boardLocation));
                boardLocation = boardLocation2;
            }

            Location dispenseLocation =
                    Utils2D.calculateBoardPlacementLocation(boardLocation, placement.getLocation());

            MovableUtils.moveToLocationAtSafeZ(pasteDispenser, dispenseLocation);

            pasteDispenser.dispense(null,null,0);

            pasteDispenser.moveToSafeZ();

            // Mark the dispense as finished
            jobDispense.status = Status.Complete;

            logger.debug("Dispensed {} ", dispenseLocation);
        }
    }


    protected void doCleanup() throws Exception {
        fireTextStatus("Cleaning up.");

        // Safe Z the machine
        head.moveToSafeZ();

    }

    protected void doReset() throws Exception {
        this.job = null;
    }

    /**
     * Discard the picked part, if any. Remove the currently processing PlannedPlacement from the
     * list and mark the JobPlacement as Skipped.
     *
     * @throws Exception
     */
    protected void doSkip() throws Exception {
            //
    }

    protected boolean isJobComplete() {
        return jobDispenses.isEmpty();
    }

    /*
    @Override
    public Wizard getConfigurationWizard() {
        return new Ref(this);
    }

    public boolean isParkWhenComplete() {
        return parkWhenComplete;
    }

    public void setParkWhenComplete(boolean parkWhenComplete) {
        this.parkWhenComplete = parkWhenComplete;
    }
*/

}
