//package fs_student;
package fullsail;

import robocode.*;
import robocode.util.Utils;
import java.awt.*;
import robocode.TeamRobot;

public class FluminenseMachine extends TeamRobot {

    // Tuning
    private static final double BASE_TARGET_RANGE = 360;
    private static final double WALL_STICK = 150;
    private static final double MARGIN = 24;

    private static final double FIRE_CLOSE = 2.6;
    private static final double FIRE_MID   = 1.9;
    private static final double FIRE_FAR   = 1.1;

    // Movement state
    private int moveDir = 1;
    private long lastFlip = -99;
    private double lastEnemyEnergy = 100;
    private int lowSpeedTicks = 0;

    // Enemy state
    private double lastEnemyHeading = 0;
    private double lastScanTime = -1;

    // Virtual guns
    private static final int G_HEAD   = 0; // G_HEAD = shoot where the enemy is now
    private static final int G_LINEAR = 1; // G_LINEAR = assume enemy moves in a straight line
    private static final int G_CIRC   = 2;// G_CIRC = assume enemy keeps turning (circular movement)

    public void run() {

        // My Robo color
        // Fluminense`s colors
        setBodyColor(new Color(163, 8, 8));
        setGunColor(new Color(0, 255, 14));
        setRadarColor(Color.WHITE);
        setBulletColor(Color.WHITE);
        setScanColor(Color.WHITE);

        setAdjustRadarForGunTurn(true);
        setAdjustGunForRobotTurn(true);

        // Keep radar spinning
        setTurnRadarRightRadians(Double.POSITIVE_INFINITY);

        while (true) {
            // Simple anti-stuck
            if (Math.abs(getVelocity()) < 0.5) {
                lowSpeedTicks++;
            } else {
                lowSpeedTicks = 0;
            }

            if (lowSpeedTicks > 8) {
                moveDir = -moveDir;
                setTurnRight(45);
                setAhead(160 * moveDir);
                lowSpeedTicks = 0;
            }

            execute();
        }
    }

    public void onScannedRobot(ScannedRobotEvent e) {

        // Radar lock
        double absBearing = getHeadingRadians() + e.getBearingRadians();
        double radarTurn = Utils.normalRelativeAngle(absBearing - getRadarHeadingRadians());
        double overshoot = Math.min(Math.atan(36.0 / Math.max(1, e.getDistance())), Math.PI / 4);
        if (radarTurn < 0) {
            setTurnRadarRightRadians(radarTurn - overshoot);
        } else {
            setTurnRadarRightRadians(radarTurn + overshoot);
        }

        // Energy drop = enemy fired (dodge)
        double energyDrop = lastEnemyEnergy - e.getEnergy();
        boolean enemyFired = energyDrop >= 0.1 && energyDrop <= 3.1;
        lastEnemyEnergy = e.getEnergy();

        if (enemyFired && getTime() - lastFlip > 10) {
            moveDir = -moveDir;
            lastFlip = getTime();
            double dodgeAngle = absBearing + Math.PI / 2 * moveDir;
            double turn = Utils.normalRelativeAngle(dodgeAngle - getHeadingRadians());
            setTurnRightRadians(turn);
            setAhead(120 * moveDir);
        }

        // Orbit movement around enemy
        double enemyX = getX() + e.getDistance() * Math.sin(absBearing);
        double enemyY = getY() + e.getDistance() * Math.cos(absBearing);

        double targetRange = BASE_TARGET_RANGE;
        if (e.getDistance() < 250) {
            targetRange = 280;
        } else if (e.getDistance() > 550) {
            targetRange = 430;
        }
        // Making it a little random
        double lateralJitter = (Math.random() - 0.5) * 0.35;
        double orbitAngle = absBearing + (Math.PI / 2 + lateralJitter) * moveDir;

        Point dest = clampToField(project(enemyX, enemyY, orbitAngle, targetRange), WALL_STICK);
        if (!inside(dest, WALL_STICK * 0.8)) {
            orbitAngle += Math.toRadians(20) * moveDir;
            dest = clampToField(project(enemyX, enemyY, orbitAngle, targetRange), WALL_STICK);
        }

        goTo(dest);

        // Enemy motion info (for targeting)
        double enemyHeading = e.getHeadingRadians();
        double enemyVelocity = e.getVelocity();

        double turnRate = 0;
        if (lastScanTime >= 0) {
            double dt = Math.max(1, getTime() - lastScanTime);
            turnRate = Utils.normalRelativeAngle(enemyHeading - lastEnemyHeading) / dt;
        }
        lastEnemyHeading = enemyHeading;
        lastScanTime = getTime();

        // Fire Power + bullet speed (same for aim and real shot)
        double firePower = chooseFirePower(e.getDistance(), getEnergy());
        double bulletSpeed = 20 - 3 * firePower;

        // Virtual guns: head-on / linear / circular
        double headOnAngle = absBearing;
        double linearAngle = computeLinearAim(enemyX, enemyY, enemyVelocity, enemyHeading, bulletSpeed);
        double circularAngle = computeCircularAim(enemyX, enemyY, enemyVelocity, enemyHeading, turnRate, bulletSpeed);

        if (Double.isNaN(linearAngle)) {
            linearAngle = headOnAngle;
        }
        if (Double.isNaN(circularAngle)) {
            circularAngle = headOnAngle;
        }
        // Choose gun index based on simple accuracy
        int bestGun = pickBestGun(e, turnRate);

        double aimAngle;
        if (bestGun == G_LINEAR) {
            aimAngle = linearAngle;
        } else if (bestGun == G_CIRC) {
            aimAngle = circularAngle;
        } else {
            aimAngle = headOnAngle;
        }
        // Turn gun and fire
        double gunTurn = Utils.normalRelativeAngle(aimAngle - getGunHeadingRadians());
        setTurnGunRightRadians(gunTurn);

        if (getGunHeat() == 0 && Math.abs(gunTurn) < Math.toRadians(9) && getEnergy() > 0.5) {
            setFire(firePower);
        }
    }

    // Choose best gun using accuracy
    private int pickBestGun(ScannedRobotEvent e, double turnRate) {
        double dist = e.getDistance();
        double vel = Math.abs(e.getVelocity());


        if (dist < 140) {
            return G_HEAD;
        }


        if (Math.abs(turnRate) > 0.04) {
            return G_CIRC;
        }


        if (vel > 4.0 && Math.abs(turnRate) < 0.03) {
            return G_LINEAR;
        }


        return G_LINEAR;
    }

    // Linear targeting
    private double computeLinearAim(double enemyX, double enemyY, double v, double h, double bulletSpeed) {
        double dx = enemyX - getX();
        double dy = enemyY - getY();

        double vx = v * Math.sin(h);
        double vy = v * Math.cos(h);

        double a = vx * vx + vy * vy - bulletSpeed * bulletSpeed;
        double b = 2 * (dx * vx + dy * vy);
        double c = dx * dx + dy * dy;

        double t;

        if (Math.abs(a) < 1e-6) {
            if (Math.abs(b) < 1e-6) return Double.NaN;
            t = -c / b;
            if (t <= 0) return Double.NaN;
        } else {
            double disc = b * b - 4 * a * c;
            if (disc < 0) return Double.NaN;
            double sqrt = Math.sqrt(disc);
            double t1 = (-b - sqrt) / (2 * a);
            double t2 = (-b + sqrt) / (2 * a);
            if (t1 > 0 && t2 > 0) {
                t = Math.min(t1, t2);
            } else {
                t = Math.max(t1, t2);
            }
            if (t <= 0) return Double.NaN;
        }

        double px = enemyX + vx * t;
        double py = enemyY + vy * t;

        px = clamp(px, MARGIN, getBattleFieldWidth() - MARGIN);
        py = clamp(py, MARGIN, getBattleFieldHeight() - MARGIN);

        return Math.atan2(px - getX(), py - getY());
    }

    // Circular targeting
    private double computeCircularAim(double enemyX, double enemyY, double v, double h, double w, double bulletSpeed) {
        double px = enemyX;
        double py = enemyY;
        double heading = h;

        double bx = getX();
        double by = getY();

        double t = 0;

        for (int i = 0; i < 120; i++) {
            double dx = px - bx;
            double dy = py - by;
            double dist = Math.hypot(dx, dy);

            if (bulletSpeed * t >= dist) {
                break;
            }

            heading += w;
            px += v * Math.sin(heading);
            py += v * Math.cos(heading);

            if (px < MARGIN || px > getBattleFieldWidth() - MARGIN) {
                heading = Math.PI - heading;
                px = clamp(px, MARGIN, getBattleFieldWidth() - MARGIN);
            }

            if (py < MARGIN || py > getBattleFieldHeight() - MARGIN) {
                heading = -heading;
                py = clamp(py, MARGIN, getBattleFieldHeight() - MARGIN);
            }

            t += 1.0;
        }

        return Math.atan2(px - bx, py - by);
    }


    public void onHitWall(HitWallEvent e) {
        setTurnRight(90);
        moveDir = -moveDir;
        setAhead(160 * moveDir);
        lastFlip = getTime();
    }


    public void onHitByBullet(HitByBulletEvent e) {
        moveDir = -moveDir;
        double turn = Utils.normalRelativeAngle(Math.PI / 2 - e.getBearingRadians());
        setTurnRightRadians(turn);
        setAhead(160 * moveDir);
    }

    // Helper classes(Private)
    private static class Point {
        double x;
        double y;

        Point(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }

    // Geometry helpers (Private)
    private Point project(double x, double y, double ang, double dist) {
        double nx = x + Math.sin(ang) * dist;
        double ny = y + Math.cos(ang) * dist;
        return new Point(nx, ny);
    }


    private Point clampToField(Point p, double stick) {
        double minX = Math.max(MARGIN, stick);
        double minY = Math.max(MARGIN, stick);
        double maxX = Math.min(getBattleFieldWidth() - MARGIN, getBattleFieldWidth() - stick);
        double maxY = Math.min(getBattleFieldHeight() - MARGIN, getBattleFieldHeight() - stick);

        double cx = clamp(p.x, minX, maxX);
        double cy = clamp(p.y, minY, maxY);

        return new Point(cx, cy);
    }

    private boolean inside(Point p, double stick) {
        return p.x >= stick && p.x <= getBattleFieldWidth() - stick &&
                p.y >= stick && p.y <= getBattleFieldHeight() - stick;
    }

    private double clamp(double v, double lo, double hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    // goTo = turn towards a point and move (can go backwards if angle is big)
    private void goTo(Point dest) {
        double angle = Math.atan2(dest.x - getX(), dest.y - getY());
        double turn = Utils.normalRelativeAngle(angle - getHeadingRadians());
        double dir = 1.0;

        if (Math.abs(turn) > Math.PI / 2) {
            turn = Utils.normalRelativeAngle(turn + Math.PI);
            dir = -1.0;
        }

        setTurnRightRadians(turn);
        setAhead(140 * dir);

        double dx = Math.min(dest.x - MARGIN, getBattleFieldWidth() - MARGIN - dest.x);
        double dy = Math.min(dest.y - MARGIN, getBattleFieldHeight() - MARGIN - dest.y);

        if (dx < 30 || dy < 30) {
            setAhead(90 * dir);
        }
    }


    private double chooseFirePower(double dist, double energy) {
        double base;
        if (dist > 520) {
            base = FIRE_FAR;
        } else if (dist > 300) {
            base = FIRE_MID;
        } else {
            base = FIRE_CLOSE;
        }

        if (energy < 20) {
            base = Math.min(base, 1.2);
        }
        if (energy < 10) {
            base = 0.7;
        }

        if (base < 0.1) base = 0.1;
        if (base > 3.0) base = 3.0;

        return base;
    }
}
