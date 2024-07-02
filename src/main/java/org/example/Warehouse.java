package org.example;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@Log4j2
@Getter
public class Warehouse extends Thread {

    private final List<Block> storage = new ArrayList<>();

    // Пришлось добавить обычную коллекцию, т.к. ее модификация происходит в "lock"
    // Плюс не использую коллекцию с занятия, потому что там не реализованы методы remove, size и тд,
    // а самому это доделывать нет времени
    private final Queue<Truck> arrivedTruck = new LinkedList<>();
    private final AtomicBoolean isExitNotFree = new AtomicBoolean(false);

    private final Lock lock = new ReentrantLock();
    private final Condition condition = lock.newCondition();
    private final Condition conditionWarehouse = lock.newCondition();

    /**
     * Для отладки
     */
    private final int delay = 1;

    public Warehouse(String name) {
        super(name);
    }

    public Warehouse(String name, Collection<Block> initialStorage) {
        this(name);
        storage.addAll(initialStorage);
    }

    @Override
    public void run() {
        Truck truck;
        while (!currentThread().isInterrupted()) {
            lock.lock();
            try {
                truck = getNextArrivedTruck();
                if (truck == null) {
                    try {
                        sleep(100 * delay);
                    } catch (InterruptedException e) {
                        if (currentThread().isInterrupted()) {

                            break;
                        }
                    }
                    continue;
                }
                if (truck.getBlocks().isEmpty()) {
                    loadTruck(truck);
                } else {
                    unloadTruck(truck);
                }
                conditionWarehouse.signalAll();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                lock.unlock();
            }
        }
        log.info("Warehouse thread interrupted");

    }

    private void loadTruck(Truck truck) {
        log.info("Loading truck {}", truck.getName());
        Collection<Block> blocksToLoad = getFreeBlocks(truck.getCapacity());
        try {
            sleep(10L * blocksToLoad.size() * delay);
        } catch (InterruptedException e) {
            log.error("Interrupted while loading truck", e);
        }
        truck.getBlocks().addAll(blocksToLoad);
        log.info("Truck loaded {}", truck.getName());
    }

    private Collection<Block> getFreeBlocks(int maxItems) {
        //TODO необходимо реализовать потокобезопасную логику по получению свободных блоков
        //TODO 1 блок грузится в 1 грузовик, нельзя клонировать блоки во время загрузки
        List<Block> blocks = new ArrayList<>();
        for (int i = 0; i < maxItems; i++) {
            blocks.add(storage.getFirst());
            storage.removeFirst();
        }
        return blocks;
    }

    private void returnBlocksToStorage(List<Block> returnedBlocks) {
        //TODO реализовать потокобезопасную логику по возврату блоков на склад
        storage.addAll(returnedBlocks);
    }

    private void unloadTruck(Truck truck) {
        log.info("Unloading truck {}", truck.getName());
        List<Block> arrivedBlocks = truck.getBlocks();
        try {
            sleep(10L * arrivedBlocks.size() * delay);
        } catch (InterruptedException e) {
            log.error("Interrupted while unloading truck", e);
        }
        returnBlocksToStorage(arrivedBlocks);
        truck.getBlocks().clear();
        log.info("Truck unloaded {}", truck.getName());
    }

    private Truck getNextArrivedTruck() throws InterruptedException {
        //TODO необходимо реализовать логику по получению следующего прибывшего грузовика внутри потока склада
        while (arrivedTruck.isEmpty()) {
            condition.await();
        }

        return arrivedTruck.poll();
    }


    public void arrive(Truck truck){
        // Почему-то без флага в "lock" попадают несколько элементов,
        // из-за этого пока в "arrivedTruck" не будет 0 элементов,
        // поток лочится на 145 строке и ждет завершения всех потоков в коллекции,
        // только после этого начинает отправляться на другой склад.
        // Не совсем понимаю с чем это связано.
        while (isExitNotFree.get()) {}

        lock.lock();
        try {
            isExitNotFree.set(true);
            arrivedTruck.add(truck);

            //TODO необходимо реализовать логику по сообщению потоку склада о том, что грузовик приехал
            condition.signalAll();

            //TODO так же дождаться разгрузки блоков, при возврате из этого метода - грузовик покинет склад
            while (truck.getBlocks().isEmpty()) {
                conditionWarehouse.await();
            }

            isExitNotFree.set(false);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }
}
