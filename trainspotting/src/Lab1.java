import TSim.*;

import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.HashMap;

public class Lab1 {
    public TSimInterface tsi;
    public static final int SWITCH_LEFT = 0x01;
    public static final int SWITCH_RIGHT = 0x02;
    private Semaphore[] railSemaphores;
    final int semaNum = 9;
    final int maxSpeed = 20;

    public static final int UP_WARD = 0x01;
    public static final int DOWN_WARD = 0x02;

    static final Map<String, int[]> sensorPosMap = new HashMap<>();
    final int[][] sensorPos = {{15, 3}, {7, 3},{15, 5}, {12, 5}, {6, 7}, {8, 5},
            {10, 7}, {9, 8}, {13, 7}, {14, 8}, {19, 9}, {11, 9}, {8, 9}, {12, 10},{7, 10},
            {1, 10}, {7, 11}, {15, 11}, {5, 13}, {15, 13}};

    public HashMap<Integer, Integer> sensorSectionMap;
    public HashMap<Integer, Integer[]> switchDirMap;

    public Lab1(int speed1, int speed2) {
        if(Math.abs(speed1) > maxSpeed || Math.abs(speed2) > maxSpeed ) {
            System.out.println("Over max speed");
            return;
        }
        tsi = TSimInterface.getInstance();
        railSemaphores = new Semaphore[semaNum];
        for (int i = 0; i < semaNum; i++) {
            railSemaphores[i] = new Semaphore(1); // binary semaphore
        }

        for (int i = 0; i < sensorPos.length; i++) {
            sensorPosMap.put("sensor" + i, sensorPos[i]);
        }

        Train train1 = new Train(1, DOWN_WARD);
        Train train2 = new Train(2, UP_WARD);
        Thread t1 = new Thread(train1);
        Thread t2 = new Thread(train2);
        initialize();

        try {
            train1.setSpeed(speed1);
            train2.setSpeed(speed2);
            t1.start();
            t2.start();
        }
        catch(Exception e){
            e.printStackTrace();
            System.exit(1);
        }
    }

    public void initialize(){
        // <hashed sensor position, section Id>
        sensorSectionMap = new HashMap<>();

        // Initialize sensor mapping
        // key: hashed sensor position, value: section Id
        int[] sectionIds = {0, 0, 1, 1, 2, 2, 2, 2, 3, 4, 5, 6, 6, 7, 7, 8, 9, 9, 10, 10};

        for (int i = 0; i < sensorPosMap.size(); i++) {
            sensorSectionMap.put(getHashedSensorPos("sensor" + i), sectionIds[i]);
        }

        // <hashed (currentSectionId as x targetSectionId as y), switchDir>
        switchDirMap = new HashMap<>();

        // Initialize switch mapping
        // key: hashed (currentSectionId as x targetSectionId as y), value: [switchDir, switchPos]
        int[][] switchMappings = {
                {3, 5, SWITCH_RIGHT, getHashed(17, 7)},
                {4, 5, SWITCH_LEFT, getHashed(17, 7)},
                {9, 8, SWITCH_LEFT, getHashed(3, 11)},
                {10, 8, SWITCH_RIGHT, getHashed(3, 11)},
                {6, 5, SWITCH_RIGHT, getHashed(15, 9)},
                {7, 5, SWITCH_LEFT, getHashed(15, 9)},
                {7, 8, SWITCH_RIGHT, getHashed(4, 9)},
                {6, 8, SWITCH_LEFT, getHashed(4, 9)}
        };

        for (int[] mapping : switchMappings) {
            switchDirMap.put(getHashed(mapping[0], mapping[1]), new Integer[]{mapping[2], mapping[3]});
            switchDirMap.put(getHashed(mapping[1], mapping[0]), new Integer[]{mapping[2], mapping[3]});
        }
        // Initialize the rail semaphore occupied by the train
        railSemaphores[1].tryAcquire();
        railSemaphores[7].tryAcquire();
    }

    public static int getHashedSensorPos(String sensorName){
        return getHashed(sensorPosMap.get(sensorName)[0], sensorPosMap.get(sensorName)[1]);
    }

    public static int getHashed(int xPos, int yPos){
        return 100 * xPos + yPos;
    }

    public static int[] getUnHashed(int val){
        int[] xy = new int[2];
        xy[0] = val / 100;
        xy[1] = val % 100;
        return xy;
    }

    class Train implements Runnable{
        final int Id;
        private int speed;
        private int direction;
        private int previousSpeed;
        private boolean hasStopped;
        private int lastSectionId = -1;

        public Train(int id, int dir){
            Id = id;
            direction = dir;
            hasStopped = true;
        }

        public void setSpeed(int speedIn) throws CommandException{
            tsi.setSpeed(this.Id, speedIn);
            this.speed = speedIn;
        }

        @Override
        public void run(){
            SensorEvent sensorEvent;
            int hashedPos;
            int sectionId;
            Rail rail;
            Track track;
            int[] stopPos = {getHashedSensorPos("sensor0"),getHashedSensorPos("sensor2"),
                    getHashedSensorPos("sensor17"), getHashedSensorPos("sensor19")};
            int[] downAcquirePos = {getHashedSensorPos("sensor1"), getHashedSensorPos("sensor3"),
                    getHashedSensorPos("sensor12"), getHashedSensorPos("sensor14")};
            int[] upAcquirePos = {getHashedSensorPos("sensor16"),getHashedSensorPos("sensor18"),
                    getHashedSensorPos("sensor11"), getHashedSensorPos("sensor13")};

            while(true){
                try{
                    sensorEvent = tsi.getSensor(this.Id);
                    hashedPos = getHashed(sensorEvent.getXpos(), sensorEvent.getYpos());
                    if (sensorSectionMap.containsKey(hashedPos)) {
                        sectionId = sensorSectionMap.get(hashedPos);
                    } else {
                        System.out.println("No mapping for sensor at position: " + hashedPos);
                        continue; // Skip this event if no mapping
                    }

                    // Release the semaphore in the last secetion
                    release(sectionId, direction);

                    if(sensorEvent.getStatus() == SensorEvent.ACTIVE) {
                        rail = new Rail(sectionId);
                        track = new Track(rail, direction);

                        // acquire next possible rail id (max = 2)
                        int[] nextRailIds = track.getNextRail();

                        if(((sectionId == 0 || sectionId == 1) && direction == UP_WARD)
                                || ((sectionId == 9 || sectionId == 10) && direction == DOWN_WARD)) {
                            trainStop(hashedPos);
                        }
                        // special case in cross part to get the next rail semaphore
                        if (crossRegionSemaAcquire(hashedPos, direction)) continue;

                        // don't need to get the next rail semaphore
                        if (inList(hashedPos, stopPos)) continue;
                        if (inList(hashedPos, upAcquirePos) && direction == DOWN_WARD) continue;
                        if (inList(hashedPos, downAcquirePos) && direction == UP_WARD) continue;

                        if (sectionId == 2) {
                            // Make sure the train can hold the semaphore 1/2
                            lastSectionId = sectionId;
                            if (direction == DOWN_WARD && hashedPos == getHashedSensorPos("sensor5")) nextRailIds[0] = 4; //special case, the next rail of section2 is default to 3
                            continue;
                        }
                        normalRegionSemaAcquire(sectionId, nextRailIds, track);

                        if (sectionId > 1 && sectionId < 8) // reset the hasStopped signal
                            hasStopped = false;

                        // print semaphore states
                        for (int i = 0; i < railSemaphores.length ; i++) {
                            System.out.print(i+": "+railSemaphores[i].availablePermits()+ ".   ");
                        }
                        System.out.println();

                    }
                }
                catch (CommandException e){
                    e.printStackTrace();
                }
                catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

        }

        public void trainStop(int pos) throws CommandException, InterruptedException{
            if (!hasStopped) {
                this.previousSpeed = this.speed;
                this.stop(direction, pos);
            }
        }

        public void stop(int direction, int hashedPos) throws InterruptedException, CommandException {
            final int[] upwardPos = {getHashedSensorPos("sensor0"), getHashedSensorPos("sensor2")};
            final int[] downwardPos = {getHashedSensorPos("sensor17"), getHashedSensorPos("sensor19")};

            if((direction == UP_WARD && inList(hashedPos, upwardPos)) ||
                    (direction == DOWN_WARD && inList(hashedPos, downwardPos))){
                this.setSpeed(0);
                Thread.sleep( 1000 + (20 * Math.abs(this.previousSpeed)));
                hasStopped = true;
                if(direction == UP_WARD) this.direction = DOWN_WARD;
                else this.direction = UP_WARD;
                this.setSpeed( - this.previousSpeed);
            }
        }

        public boolean release(int sectionId, int direction){
            if(direction == UP_WARD && (lastSectionId == 3 || lastSectionId == 4)) return false;
            if (lastSectionId != -1 && lastSectionId != sectionId) {
                Semaphore s = new Rail(lastSectionId).semaphore;
                if(s != null){
                    s.release();
                }
                lastSectionId = sectionId;
                return  true;
            }else return false;
        }

        public boolean crossRegionSemaAcquire(int pos, int direction) throws CommandException, InterruptedException{
            if (direction == UP_WARD) return false;
            if ( pos == getHashedSensorPos("sensor1") || pos == getHashedSensorPos("sensor3")) {
                if(!railSemaphores[0].tryAcquire()) {
                    this.previousSpeed = this.speed;
                    this.setSpeed(0);
                    railSemaphores[0].acquire();
                    this.setSpeed(this.previousSpeed);
                }
                return true;
            }
            return false;
        }

        public void normalRegionSemaAcquire(int sectionId, int[] nextRailIds, Track track ) throws CommandException, InterruptedException{
            Semaphore s = new Rail(nextRailIds[0]).semaphore;

            if ( s != null && s.tryAcquire()) { //succeed immediately
                track.getSwitchSet(sectionId, nextRailIds[0]);
                lastSectionId = sectionId;
            }
            else if(nextRailIds[1] == -1){ // the first failed = must wait
                this.previousSpeed = this.speed;
                this.setSpeed(0);
                s.acquire();
                track.getSwitchSet(sectionId, nextRailIds[0]);
                this.setSpeed(this.previousSpeed);
                lastSectionId = sectionId;
            }
            else{
                s = new Rail(nextRailIds[1]).semaphore;
                if(s != null && s.tryAcquire()){
                    track.getSwitchSet(sectionId, nextRailIds[1]);
                    lastSectionId = sectionId;
                }
            }
        }

        private boolean inList(int pos, int[] list){
            for (int p : list){
                if (p == pos) return true;
            }
            return false;
        }

    }
    public class Rail{
        final int Id;
        final Semaphore semaphore;

        public Rail(int id){
            this.Id = id;
            if (id > 1) semaphore = railSemaphores[id - 2];
            else semaphore = null;
        }

    }
    public class Track{
        final int direction; // UP_WRAD/DOWN_WARD
        private int[] nextRailId; // 0: next rail, 1: alternative rail
        private Rail rail;

        public Track(Rail r, int dir){
            rail = r;
            direction = dir;
            nextRailId = new int[2];
        }

        private int[] getNextRail(){
            if( rail.Id == 0 || rail.Id == 1) {nextRailId[0] = 2; nextRailId[1] = -1;}
            if( rail.Id == 2 ) {
                if(direction == UP_WARD) {nextRailId[0] = -1; nextRailId[1] = -1;}
                if(direction == DOWN_WARD) {nextRailId[0] = 3; nextRailId[1] = -1;} // special
            }
            if( rail.Id == 3 || rail.Id == 4 ) {
                if(direction == UP_WARD) {nextRailId[0] = 2; nextRailId[1] = -1;}
                if(direction == DOWN_WARD) {nextRailId[0] = 5; nextRailId[1] = -1;}
            }
            if( rail.Id == 5 ) {
                if(direction == UP_WARD) {nextRailId[0] = 3; nextRailId[1] = 4;}
                if(direction == DOWN_WARD) {nextRailId[0] = 6; nextRailId[1] = 7;}
            }
            if( rail.Id == 6 || rail.Id == 7) {
                if(direction == UP_WARD) {nextRailId[0] = 5; nextRailId[1] = -1;}
                if(direction == DOWN_WARD) {nextRailId[0] = 8; nextRailId[1] = -1;}
            }
            if( rail.Id == 8 ) {
                if(direction == UP_WARD) {nextRailId[0] = 6; nextRailId[1] = 7;}
                if(direction == DOWN_WARD) {nextRailId[0] = 9; nextRailId[1] = 10;}
            }
            if( rail.Id == 9 || rail.Id == 10 ) {nextRailId[0] = 8; nextRailId[1] = -1;}
            return nextRailId;
        }

        public void getSwitchSet(int currentSectionId, int nextSectionId) throws CommandException{
            if(switchDirMap.get(getHashed(currentSectionId, nextSectionId)) == null) return;
            int switchDir = switchDirMap.get(getHashed(currentSectionId, nextSectionId))[0];
            int[] switchPos = getUnHashed(switchDirMap.get(getHashed(currentSectionId, nextSectionId))[1]);
            tsi.setSwitch(switchPos[0], switchPos[1], switchDir);
        }
    }
}