# Kafka

## 简介
市面上常见的消息队列产品：XXMQ Redis Kafka
             
Kafka同步写入内存，但是异步写入磁盘。解决Kafka高性能的核心思想是不是减少内核态和用户态的转换？
MMF，Memory Mapped File。磁盘空间映射到内核级别的内存空间 —— pageCache，用户空间的程序只需要把数据写入到内核空间，
就等价地写入到了磁盘之中。pageCache什么时候会把数据刷新到磁盘呢？由OS决定，这样就极大地降低了写入IO的损失，应用程序
也不会应为等待写入磁盘而阻塞挂起。这里有个问题，应用程序过来的数据太多太快，pageCache不够怎么办？
             
内核级别的内存空间，不同的进程是可以共享的，这一点不同于用户空间。即使应用挂了，也不会影响内核空间的内存，还是会有操作
系统刷入到磁盘。但是操作系统内核不稳定或者断电，就会造成pageCache数据丢失，会造成问题。最最保险的话是直接拿application
以阻塞的方式写到磁盘，但会损失性能。
             
数据被消费者读出的时候，直接通过内核空间将数据传输出去，并不抵达用户空间，0拷贝。DMA加进来会大大减少磁盘读写时CPU的中断次数

### 为什么kafka不支持主从分离？

首先明确一下：主从分离与否没有绝对的优劣，它仅仅是一种架构设计，各自有适用的场景。  

第二、如你所说，Redis和MySQL都支持主从读写分离，我个人觉得这和它们的使用场景有关。对于那种读操作很多而写操作相对不频繁的负载类型而言，
采用读写分离是非常不错的方案——我们可以添加很多follower横向扩展，提升读操作性能。反观Kafka，它的主要场景还是在消息引擎而不是以数据存储的方式对外提供读服务，
通常涉及频繁地生产消息和消费消息，这不属于典型的读多写少场景，因此读写分离方案在这个场景下并不太适合。写的时候同时读的情形比HDFS等框架要多得多，脏读、幻读
等数据不一致的情况发生的几率高出很多  

第三、Kafka副本机制使用的是异步消息拉取，因此存在leader和follower之间的不一致性。如果要采用读写分离，必然要处理副本lag引入的一致性问题，比如如何实现
read-your-writes、如何保证单调读（monotonic reads）以及处理消息因果顺序颠倒的问题。相反地，如果不采用读写分离，所有客户端读写请求都只在Leader上处理
也就没有这些问题了——当然最后全局消息顺序颠倒的问题在Kafka中依然存在，常见的解决办法是使用单分区，其他的方案还有version vector，但是目前Kafka没有提供。
最后、社区正在考虑引入适度的读写分离方案，比如允许某些指定的follower副本（主要是为了考虑地理相近性）可以对外提供读服务。当然目前这个方案还在讨论中。

曾经看到过一句话，从某种意义上说，读写分离是由于设计上的缺陷而采取的权宜之计。所谓的读写分离就是主写从读，主要为了分担主节点的读操作的负载，因此读写分离
适用于读多写少的场景。Kafka作为一个消息中间件或者流处理平台，更多的场景是生产者不断的将消息写入broker，消费者不断的从broker读数据进行消费，因此读写分离
并不适合kafka。如果采用了读写分离，反而会产生一些弊端，比如消费者偏移量的维护更为困难，另外，kafka的follower副本采用的是异步拉取leader副本的方式实现数据同步，
因此leader和follower之间必定存在着一定时间范围内的数据不一致，实现主从分离还需要解决数据一致性的问题。从另外一个角度上说，kafka没有必要实现读写分离，因此
它有着更为适合自己的负载方式，kafka通过为主题设置多个分区的方式实现负载，不同的分区的leader尽可能的分布在不同的broker，实现了读与写的同时负载。

链接：  
https://www.zhihu.com/question/327925275/answer/731053968  
https://www.zhihu.com/question/327925275/answer/705690755
为什么Kafka不支持读写分离：https://blog.csdn.net/zl1zl2zl3/article/details/87982038

## 单机安装

1. 首先安装JDK：
    i    先下载`jdk-13.0.1_linux-x64_bin.rpm`  
    ii   如果有原来的JDK则可以卸载：rpm -e `rpm -qa | grep jdk`  
    iii  `rpm -ivh ./jdk-13.0.1_linux-x64_bin.rpm`  
    iv   `vim ~/.bashrc` 配置环境变量，在文件的最后加入：
          ```
          export JAVA_HOME
          export PATH
          export CLASSPATH
          ```
          保存退出之后执行`source ~/.bashrc`  
          
2. 安装Zookeeper，`tar xf ...` 在`/usr/local/zk/conf`下复制zoo_sample.cfg到zoo.cfg,并修改中的条目：
`dataDir=/root/zkdata`, 然后`mkdir /root/zkdata`创建该目录  

3. 启动zk：`/usr/local/zk/bin/zkServer.sh start zoo.cfg`, 可以jps验证是否出现QuorumPeerMain进程.进一步验证：
```
[root@Kafka_1 bin]# ./zkServer.sh status
JMX enabled by default
Using config: /usr/local/zk/bin/../conf/zoo.cfg
Mode: standalone
```
standalone出现的话就算成功了  

4. 在`/user/local`目录下解压`kafka_2.13-2.4.1.tar`: `tar xf kafka_2.13-2.4.1.tar`  
5. 配置`/usr/local/kafka/config/server.properties`:  
    i    打开注释并配置`listeners=PLAINTEXT://Kafka_1:9092`注意：这里要写主机名，不要写IP  
    ii   配置`log.dir`，这里是当前broker节点存储: `log.dirs=/usr/local/kafka/logs`(可以自己创建，没有会自动创建)  
    iii  配置zookeeper服务的地址：`zookeeper.connect=Kafka_1:2181`这里Kafka_1是因为我们把Zookeeper安装到了
         Kafka_1上  
6. 启动Kafka：`/usr/local/kafka/bin/kafka-server-start.sh -daemon /usr/local/kafka/config/server.properties`  
7. 关闭Kafka：`/usr/local/kafka/bin/kafka-server-stop.sh`  
8. 可选。配置环境变量使得执行命令更简单：打开`/etc/profile`, 在末尾加上
    ```
    export KAFKA_HOME=/usr/local/kafka
    export PATH=$KAFKA_HOME/bin:$PATH
    ```
   然后`source /etc/profile` 生效

## 在Kafka里面创建Topic
`kafka-topics.sh --bootstrap-server Kafka_1:9092 --create --topic topic01 --partitions 3 --replication-factor 1`
`--replication-factor`的个数不能大于broker的个数，大于的话就会有broker存储两个或以上的副本，没有意义了，Kafka为此也会报错。

## 首先开启Consumer等着消费
`kafka-console-consumer.sh --bootstrap-server Kafka_1:9092 --topic topic01 --group group1` 这里指定了Kafka主机、
topic和consumer group，同一个consumer group下的consumer消费消息的时候，一个消息只能被消费一次；不同的consumer group可以
消费同一个消息

## 然后开启Producer发出消息
`kafka-console-producer.sh --broker-list Kafka_1:9092 --topic topic01` 

## 观察到的现象
consumer被指定了不同的partitions，不同的partitions之间有个负载均衡，大概均分消息。一旦consumer数多于partition数，就会有
consumer现在备用，一旦有consumer退出或挂机，就立刻顶上来开始消费消息。不同的consumer group共享消息，各个组件之间处理和分享
消息的逻辑在consumer groups之间都是保持相同的

## 集群安装

1. 每一台机器上其他配置基本都一样，但是有以下要修改的地方：  
zk/conf/zoo.cfg里面，最下面加上zk集群的各台机器的信息以及他们的zk数据传输端口2888和主从选举端口3888：
```
server.1=192.168.1.11:2888:3888
server.2=192.168.1.12:2888:3888
server.3=192.168.1.13:2888:3888
```
并分发给所有的机器，并保证各个机器上的数据目录`dataDir=/root/zkdata`（或者其他路径）都已经创建，并且在这个目录下面有个叫做myid的文件，里面分别
写了1、2、3作为他们的ID. 注意：这里必须用IP而不能用hostname，否则只能有两个节点，有一个节点会加不进来，报错：
`Have smaller server identifie`与启动顺序无关，但尽量按照ID的从小到大顺序：
https://grokbase.com/t/zookeeper/user/142tpev8rx/new-zookeeper-server-fails-to-join-quorum-with-msg-have-smaller-server-identifie

2. 启动各个zk节点  
3. 在各台机器上修改/usr/local/kafka/config/server.properties，写出所有机器及其及端口：`zookeeper.connect=Kafka_1:2181,Kafka_2:2181,Kafka_3:2181`  
4. 仍然修改此文件，把broker.id改为各自的编号, 把listeners后面的主机名改为各自的：`listeners=PLAINTEXT://Kafka_2:9092` `listeners=PLAINTEXT://Kafka_3:9092`
5. 启动集群的各台机器：`/usr/local/kafka/bin/kafka-server-start.sh -daemon /usr/local/kafka/config/server.properties` 去掉-daemon可以显示启动中的错误，如果出现
```
[2020-04-01 20:42:00,513] ERROR Fatal error during KafkaServer startup. Prepare to shutdown (kafka.server.KafkaServer)
kafka.common.InconsistentClusterIdException: The Cluster ID _DlNDaLWSxu0AGL_zquB_Q doesn't match stored clusterId Some(DKaQvMZ9TTKb_ar8kvIhwQ) in 
meta.properties. The broker is trying to join the wrong cluster. Configured zookeeper.connect may be wrong.
```
则删除Kafka log目录（如`/usr/local/kafka/logs`）下的meta.properties, 因为重启的话这里面的内容对不上了，重启之前删除，然后Kafka启动的时候就能自己再生成一致的了。  
6. 在Kafka集群中创建topic：`./bin/kafka-topics.sh --bootstrap-server Kafka_1:9092,Kafka_2:9092,Kafka_3:9092 --create --topic topic01 --partitions 3 --replication-factor 2`  
7. 查看已经创建了多少消息队列：`./bin/kafka-topics.sh --bootstrap-server Kafka_1:9092,Kafka_2:9092,Kafka_3:9092 --list`  
8. 查看所有topic的详细信息：`./bin/kafka-topics.sh --bootstrap-server Kafka_1:9092,Kafka_2:9092,Kafka_3:9092 --describe --topic topic01`输出：
 ```
   Topic: topic01	PartitionCount: 3	ReplicationFactor: 2	Configs: segment.bytes=1073741824
      	      Topic: topic01	Partition: 0	Leader: 0	Replicas: 0,1	Isr: 0,1
      	      Topic: topic01	Partition: 1	Leader: 2	Replicas: 2,0	Isr: 2
      	      Topic: topic01	Partition: 2	Leader: 1	Replicas: 1,2	Isr: 1,2
``` 
有机器下线的话leader可能会变，而且那台机器再上线之后也不会抢回leader身份：
```
[root@Kafka_3 kafka]# ./bin/kafka-topics.sh --bootstrap-server Kafka_1:9092,Kafka_2:9092,Kafka_3:9092 --describe --topic topic01
Topic: topic01	PartitionCount: 3	ReplicationFactor: 2	Configs: segment.bytes=1073741824
	Topic: topic01	Partition: 0	Leader: 1	Replicas: 0,1	Isr: 1
	Topic: topic01	Partition: 1	Leader: 2	Replicas: 2,0	Isr: 2
	Topic: topic01	Partition: 2	Leader: 2	Replicas: 1,2	Isr: 2,1
```
也可以创建另一个topic，2各分区3个副本（这是可以的，副本数并不大于broker数）：
```
[root@Kafka_2 kafka]# ./bin/kafka-topics.sh --bootstrap-server Kafka_1:9092,Kafka_2:9092,Kafka_3:9092 --describe --topic topic02
Topic: topic02	PartitionCount: 2	ReplicationFactor: 3	Configs: segment.bytes=1073741824
	    Topic: topic02	Partition: 0	Leader: 2	Replicas: 2,0,1	Isr: 2,1
	    Topic: topic02	Partition: 1	Leader: 1	Replicas: 1,2,0	Isr: 2,1
```

## 踩坑
Kafka_1总是连不上，报错：
```
WARN [AdminClient clientId=adminclient-1] Connection to node -1 (Kafka_1/127.0.0.1:9092) could not be established. Broker may not be available. (org.apache.kafka.clients.NetworkClient)
```
仔细读错误，注意到`Kafka_1/127.0.0.1:9092`, 最后发现是`/etc/hosts`配置不对，把127.0.0.1配置成了Kafka_1而不是localhost，改成后者就好了

## 修改Topic分区数

Kafka的分区数只能增加不能减少:
 ```
[root@Kafka_2 kafka]# ./bin/kafka-topics.sh --bootstrap-server Kafka_1:9092,Kafka_2:9092,Kafka_3:9092 --alter --topic topic02 --partitions 1
Error while executing topic command : org.apache.kafka.common.errors.InvalidPartitionsException: Topic currently has 2 partitions, which is 
higher than the requested 1.
[2020-04-01 22:40:59,956] ERROR java.util.concurrent.ExecutionException: org.apache.kafka.common.errors.InvalidPartitionsException: Topic currently has 2 partitions, which is higher than the requested 1.
```
--partitions后面的数字改成3就可以了

## 删除Topic
`kafka-topics.sh --bootstrap-server Kafka_1:9092,Kafka_2:9092,Kafka_3:9092 --delete --topic topic01`

## 集群中订阅消费消息
`kafka-console-consumer.sh --bootstrap-server Kafka_1:9092,Kafka_2:9092,Kafka_3:9092 --topic topic01 --group g1 --property print.key=true --property print.value=true --property key.separator=,`
默认不打印key只打印值，现在指定都打印，而且这里还指定了key和value之间的分隔符  
produce消息：  
`kafka-console-producer.sh --broker-list Kafka_1:9092,Kafka_2:9092,Kafka_3:9092 --topic topic01` 则消费者那一端会显示：  
`null,我是hello` key为空。显示组的信息： `kafka-consumer-groups.sh --bootstrap-server Kafka_1:9092,Kafka_2:9092,Kafka_3:9092 --list`返回所有Consumer Groups的
列表，这里只有g1. 还可以查看g1组的详细信息：
```
[root@Kafka_1 kafka]# kafka-consumer-groups.sh --bootstrap-server Kafka_1:9092,Kafka_2:9092,Kafka_3:9092 --describe --group g1

GROUP           TOPIC           PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG             CONSUMER-ID                                        HOST            CLIENT-ID
g1              topic01         0          1               1               0               consumer-g1-1-2577bd7d-23ad-4383-8ffd-c9ff5e7ffccd /192.168.1.11   consumer-g1-1
g1              topic01         1          0               0               0               consumer-g1-1-2577bd7d-23ad-4383-8ffd-c9ff5e7ffccd /192.168.1.11   consumer-g1-1
g1              topic01         2          1               1               0               consumer-g1-1-4b02fb0c-b3d0-41b0-86aa-897678c84114 /192.168.1.12   consumer-g1-1
```
其中LAG是生产与消费的差值=0，没有消息积压的情况