package com.widen.util.td;

/**
 * Class that produces a deadlock condition between 2 threads.
 */
public class DeadlockThread extends Thread
{
	public static final Object OBJ1 = "lockObject1";
	public static final Object OBJ2 = "lockObject2";
	
	public static void startThreads()
	{
		DeadlockThread dt1 = new DeadlockThread("dt1", OBJ1, OBJ2);
		DeadlockThread dt2 = new DeadlockThread("dt2", OBJ2, OBJ1);
		
		dt1.start();
		dt2.start();
	}
	
	private Object lock1;
	private Object lock2;
	private String name;
	
	public DeadlockThread(String name, Object lock1, Object lock2)
	{
		this.lock1 = lock1;
		this.lock2 = lock2;
		this.name = name;
	}
	
	public void run()
	{
		System.out.println("DeadlockThread [" + name + "] starting run");
		synchronized (lock1)
		{
			System.out.println("DeadlockThread [" + name + "] obtained lock on " + lock1);
//			try
//			{
//				Thread.sleep(500);
//			}
//			catch (InterruptedException e)
//			{
//				return;
//			}
			synchronized (lock2)
			{
				System.out.println("DeadlockThread [" + name + "] obtained lock on " + lock2);
			}
		}
	}
}
