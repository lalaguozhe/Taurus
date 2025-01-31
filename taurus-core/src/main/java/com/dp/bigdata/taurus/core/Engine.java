package com.dp.bigdata.taurus.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import com.dp.bigdata.taurus.generated.mapper.TaskAttemptMapper;
import com.dp.bigdata.taurus.generated.mapper.TaskMapper;
import com.dp.bigdata.taurus.generated.module.Host;
import com.dp.bigdata.taurus.generated.module.Task;
import com.dp.bigdata.taurus.generated.module.TaskAttempt;
import com.dp.bigdata.taurus.generated.module.TaskAttemptExample;
import com.dp.bigdata.taurus.generated.module.TaskExample;
import com.dp.bigdata.taurus.zookeeper.execute.helper.ExecuteException;
import com.dp.bigdata.taurus.zookeeper.execute.helper.ExecuteStatus;
import com.dp.bigdata.taurus.zookeeper.execute.helper.ExecutorManager;

/**
 * Engine is the default implementation of the <code>Scheduler</code>.
 * 
 * @author damon.zhu
 * @see Scheduler
 */
final public class Engine implements Scheduler {

    private static final Log LOG = LogFactory.getLog(Engine.class);

    private Map<String, Task> registedTasks; // Map<taskID, task>
    private Map<String, String> tasksMapCache; // Map<name, taskID>
    private Map<String, HashMap<String, AttemptContext>> runningAttempts; // Map<taskID,HashMap<attemptID,AttemptContext>>
    private Runnable progressMonitor;
    @Autowired
    @Qualifier("triggle.crontab")
    private Triggle crontabTriggle;
    @Autowired
    @Qualifier("triggle.dependency")
    private Triggle dependencyTriggle;
    @Autowired
    @Qualifier("filter.isAllowMutilInstance")
    private Filter filter;
    @Autowired
    private TaskAssignPolicy assignPolicy;
    @Autowired
    private TaskAttemptMapper taskAttemptMapper;
    @Autowired
    private TaskMapper taskMapper;
    @Autowired
    private IDFactory idFactory;
    @Autowired
    private ExecutorManager zookeeper;
    /**
     * Maximum concurrent running attempt number
     */
    private int maxConcurrency = 20;

    public Engine() {
        registedTasks = new ConcurrentHashMap<String, Task>();
        tasksMapCache = new ConcurrentHashMap<String, String>();
        runningAttempts = new ConcurrentHashMap<String, HashMap<String, AttemptContext>>();
    }

    /**
     * load data from the database;
     */
    public void load() {
        // load all tasks
        TaskExample example = new TaskExample();
        example.or().andStatusEqualTo(TaskStatus.RUNNING);
        example.or().andStatusEqualTo(TaskStatus.SUSPEND);
        List<Task> tasks = taskMapper.selectByExample(example);
        for (Task task : tasks) {
            registedTasks.put(task.getTaskid(), task);
        }
        // load running attempts
        TaskAttemptExample example1 = new TaskAttemptExample();
        example1.or().andStatusEqualTo(AttemptStatus.RUNNING);
        List<TaskAttempt> attempts = taskAttemptMapper.selectByExample(example1);
        for (TaskAttempt attempt : attempts) {
            Task task = registedTasks.get(attempt.getTaskid());
            AttemptContext context = new AttemptContext(attempt, task);
            registAttemptContext(context);
        }
    }

    /**
     * start the engine;
     */
    public void start() {
        new Thread(progressMonitor).start();

        while (true) {
            LOG.info("Engine trys to scan the database...");
            crontabTriggle.triggle();
            dependencyTriggle.triggle();
            List<AttemptContext> contexts = filter.filter(getReadyToRunAttempt());
            for (AttemptContext context : contexts) {
                try {
                    executeAttempt(context);
                } catch (ScheduleException e) {
                    // do nothing
                    LOG.error(e.getMessage());
                }
            }
            try {
                Thread.sleep(SCHDUELE_INTERVAL);
            } catch (InterruptedException e) {
            }
        }
    }

    /**
     * graceful shutdown the server;
     */
    public void stop() {
        // TODO Auto-generated method stub

    }

    public synchronized void registerTask(Task task) throws ScheduleException {
        if (!registedTasks.containsKey(task.getTaskid())) {
            registedTasks.put(task.getTaskid(), task);
            taskMapper.insert(task);
        } else {
            throw new ScheduleException("The task : " + task.getTaskid() + " has been registered.");
        }
    }

    public synchronized void unRegisterTask(String taskID) throws ScheduleException {
        Map<String, AttemptContext> contexts = runningAttempts.get(taskID);
        if (contexts != null && contexts.size() > 0) {
            throw new ScheduleException("There are running attempts, so cannot remove this task");
        }

        if (registedTasks.containsKey(taskID)) {
            Task task = registedTasks.get(taskID);
            task.setStatus(TaskStatus.DELETED);
            taskMapper.updateByPrimaryKeySelective(task);
            registedTasks.remove(taskID);
        }
    }

    public synchronized void updateTask(Task task) throws ScheduleException {
        if (registedTasks.containsKey(task.getTaskid())) {
            registedTasks.remove(task.getTaskid());
            registedTasks.put(task.getTaskid(), task);
            taskMapper.updateByPrimaryKeySelective(task);
        } else {
            throw new ScheduleException("The task : " + task.getTaskid() + " has not been found.");
        }
    }

    public synchronized void executeTask(String taskID, long timeout) throws ScheduleException {
        //TODO timeout
        String instanceID = idFactory.newInstanceID(taskID);
        TaskAttempt attempt = new TaskAttempt();
        String attemptID = idFactory.newAttemptID(instanceID);
        attempt.setInstanceid(instanceID);
        attempt.setTaskid(taskID);
        attempt.setStatus(AttemptStatus.INITIALIZED);
        attempt.setAttemptid(attemptID);
        attempt.setScheduletime(new Date());
        Task task = registedTasks.get(taskID);
        AttemptContext context = new AttemptContext(attempt, task);
        executeAttempt(context);
    }

    public synchronized void suspendTask(String taskID) throws ScheduleException {
        if (registedTasks.containsKey(taskID)) {
            Task task = registedTasks.get(taskID);
            task.setStatus(TaskStatus.SUSPEND);
            taskMapper.updateByPrimaryKey(task);
        } else {
            throw new ScheduleException("The task : " + taskID + " has not been found.");
        }
    }

    public synchronized void executeAttempt(AttemptContext context) throws ScheduleException {
        TaskAttempt attempt = context.getAttempt();
        Host host = assignPolicy.assignTask(context.getTask());
        attempt.setExechost(host.getIp());
        attempt.setStarttime(new Date());
        final long start = System.nanoTime();
        try {
            zookeeper.execute(context.getContext());
        } catch (ExecuteException ee) {
            attempt.setStatus(AttemptStatus.SUBMIT_FAIL);
            taskAttemptMapper.updateByPrimaryKey(attempt);
            throw new ScheduleException("Fail to execute attemptID : " + attempt.getAttemptid() + " on host : " + host.getIp());
        }
        final long end = System.nanoTime();
        LOG.info("Time (seconds) taken " + (end - start) / 1.0e9 + " to start attempt : " + context.getAttemptid());

        // update the status for TaskAttempt
        attempt.setStatus(AttemptStatus.RUNNING);
        taskAttemptMapper.updateByPrimaryKey(attempt);
        // register the attempt context
        registAttemptContext(context);
    }

    public boolean isRuningAttempt(String attemptID) {
        HashMap<String, AttemptContext> contexts = runningAttempts.get(AttemptID.getTaskID(attemptID));
        AttemptContext context = contexts.get(attemptID);
        if (context == null) {
            return false;
        } else {
            return true;
        }

    }

    public synchronized void killAttempt(String attemptID) throws ScheduleException {
        HashMap<String, AttemptContext> contexts = runningAttempts.get(AttemptID.getTaskID(attemptID));
        AttemptContext context = contexts.get(attemptID);
        if (context == null) {
            throw new ScheduleException("Unable find attemptID : " + attemptID);
        }
        try {
            zookeeper.kill(context.getContext());
        } catch (ExecuteException ee) {
            // do nothing; keep the attempt status unchanged.
            throw new ScheduleException("Fail to execute attemptID : " + attemptID + " on host : " + context.getExechost());
        }
        context.getAttempt().setStatus(AttemptStatus.KILLED);
        context.getAttempt().setEndtime(new Date());
        taskAttemptMapper.updateByPrimaryKeySelective(context.getAttempt());
        unregistAttemptContext(context);
    }

    public void attemptSucceed(String attemptID) {
        AttemptContext context = runningAttempts.get(AttemptID.getTaskID(attemptID)).get(attemptID);
        TaskAttempt attempt = context.getAttempt();
        attempt.setReturnvalue(0);
        attempt.setEndtime(new Date());
        attempt.setStatus(AttemptStatus.SUCCEEDED);
        taskAttemptMapper.updateByPrimaryKeySelective(attempt);
        unregistAttemptContext(context);
    }

    public void attemptExpired(String attemptID) {
        AttemptContext context = runningAttempts.get(AttemptID.getTaskID(attemptID)).get(attemptID);
        TaskAttempt attempt = context.getAttempt();
        attempt.setEndtime(new Date());
        attempt.setStatus(AttemptStatus.TIMEOUT);
        taskAttemptMapper.updateByPrimaryKeySelective(attempt);
    }

    public void attemptFailed(String attemptID) {
        AttemptContext context = runningAttempts.get(AttemptID.getTaskID(attemptID)).get(attemptID);
        TaskAttempt attempt = context.getAttempt();
        attempt.setStatus(AttemptStatus.FAILED);
        attempt.setEndtime(new Date());
        taskAttemptMapper.updateByPrimaryKeySelective(attempt);
        unregistAttemptContext(context);

        /*
         * Check whether it is necessary to retry this failed attempt. If true, insert new attempt into the database; Otherwise, do
         * nothing.
         */
        Task task = context.getTask();
        if (task.getIsautoretry()) {
            TaskAttemptExample example = new TaskAttemptExample();
            example.or().andInstanceidEqualTo(attempt.getInstanceid());
            List<TaskAttempt> attemptsOfRecentInstance = taskAttemptMapper.selectByExample(example);
            if (task.getRetrytimes() < attemptsOfRecentInstance.size() - 1) {
                //do nothing
            } else if (task.getRetrytimes() == attemptsOfRecentInstance.size() - 1) {
                //do nothing
            } else {
                LOG.info("Attempt " + attempt.getAttemptid() + " fail, begin to retry the attempt...");
                String instanceID = attempt.getInstanceid();
                TaskAttempt retry = new TaskAttempt();
                String id = idFactory.newAttemptID(instanceID);
                retry.setAttemptid(id);
                retry.setTaskid(task.getTaskid());
                retry.setInstanceid(instanceID);
                retry.setScheduletime(attempt.getScheduletime());
                retry.setStatus(AttemptStatus.DEPENDENCY_PASS);
                taskAttemptMapper.insertSelective(retry);
            }
        }
    }

    public List<AttemptContext> getAllRunningAttempt() {
        List<AttemptContext> contexts = new ArrayList<AttemptContext>();
        for (HashMap<String, AttemptContext> maps : runningAttempts.values()) {
            for (AttemptContext context : maps.values()) {
                contexts.add(context);
            }
        }
        return Collections.unmodifiableList(contexts);
    }

    public List<AttemptContext> getRunningAttemptsByTaskID(String taskID) {
        List<AttemptContext> contexts = new ArrayList<AttemptContext>();
        HashMap<String, AttemptContext> maps = runningAttempts.get(taskID);
        if (maps == null) {
            return contexts;
        }
        for (AttemptContext context : runningAttempts.get(taskID).values()) {
            contexts.add(context);
        }
        return Collections.unmodifiableList(contexts);
    }

    public AttemptStatus getAttemptStatus(String attemptID) {
        HashMap<String, AttemptContext> maps = runningAttempts.get(AttemptID.getTaskID(attemptID));
        AttemptContext context = maps.get(attemptID);
        ExecuteStatus status = null;
        try {
            status = zookeeper.getStatus(context.getContext());
        } catch (ExecuteException ee) {
            status = new ExecuteStatus(AttemptStatus.UNKNOWN);
        }
        AttemptStatus astatus = new AttemptStatus(status.getStatus());
        astatus.setReturnCode(status.getReturnCode());
        return astatus;
    }

    private List<AttemptContext> getReadyToRunAttempt() {
        List<AttemptContext> contexts = new ArrayList<AttemptContext>();
        TaskAttemptExample example = new TaskAttemptExample();
        example.or().andStatusEqualTo(AttemptStatus.DEPENDENCY_PASS);
        example.setOrderByClause("scheduleTime");
        List<TaskAttempt> attempts = taskAttemptMapper.selectByExample(example);
        for (TaskAttempt attempt : attempts) {
            Task task = registedTasks.get(attempt.getTaskid());
            contexts.add(new AttemptContext(attempt, task));
        }
        return contexts;
    }

    private void registAttemptContext(AttemptContext context) {
        HashMap<String, AttemptContext> contexts = runningAttempts.get(context.getTaskid());
        if (contexts == null) {
            contexts = new HashMap<String, AttemptContext>();
        }
        contexts.put(context.getAttemptid(), context);
        runningAttempts.put(context.getTaskid(), contexts);
    }

    private void unregistAttemptContext(AttemptContext context) {
        if (runningAttempts.containsKey(context.getTaskid())) {
            HashMap<String, AttemptContext> contexts = runningAttempts.get(context.getTaskid());
            if (contexts.containsKey(context.getAttemptid())) {
                contexts.remove(context.getAttemptid());
            }
        }
    }

    public Map<String, Task> getAllRegistedTask() {
        return Collections.unmodifiableMap(registedTasks);
    }

    public synchronized Task getTaskByName(String name) throws ScheduleException {
        if (tasksMapCache.containsKey(name)) {
            String taskID = tasksMapCache.get(name);
            return registedTasks.get(taskID);
        } else {
            TaskExample example = new TaskExample();
            example.or().andNameEqualTo(name);
            List<Task> tasks = taskMapper.selectByExample(example);
            if (tasks != null && tasks.size() == 1) {
                Task task = tasks.get(0);
                tasksMapCache.put(name, task.getTaskid());
                return task;
            } else {
                throw new ScheduleException("Cannot found tasks for the given name.");
            }
        }
    }

    public Runnable getProgressMonitor() {
        return progressMonitor;
    }

    public void setProgressMonitor(Runnable progressMonitor) {
        this.progressMonitor = progressMonitor;
    }

    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    public void setMaxConcurrency(int maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
    }
}
