/**
 * Copyright 2018 Ricoh Company, Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <Wire.h>

#define M5GO_WHEEL_ADDR     0x56
#define MOTOR_CTRL_ADDR     0x00
#define ENCODER_ADDR        0x04

#define MOTOR_RPM           150
#define MAX_PWM             255
#define DEAD_ZONE           20

int16_t speed_input0=0;
int16_t speed_input1=0;
int motor0 = 0;
int motor1 = 0;



String serialRead();
int splitParam( String inStr, int *param1, int *param2 );

void setMotor(int16_t pwm0, int16_t pwm1);
void readEncder();

void stop();
void move(int16_t speed, uint16_t duration );
void turn(int16_t speed, uint16_t duration );
void turn0(int16_t speed, uint16_t duration );
void turn1(int16_t speed, uint16_t duration );
void rotate(int16_t speed, uint16_t duration );



void setup() {
  Serial.begin(115200);     // SERIAL

  Wire.begin();
  Wire.setClock(400000UL);  // Set I2C frequency to 400kHz
  delay(500);

  setMotor( 0, 0 );

}

void loop() {
  int speed=0;
  int delay_ms=0;
  String RcvCmd ="";

  RcvCmd = serialRead();
  RcvCmd.trim();
  if ( !(RcvCmd.equals("")) ) {
    Serial.print("rcv=["+RcvCmd + "]\n");
    if ( RcvCmd.equals("go") ) {
      move(80, 1000); // test move
    } else if ( RcvCmd.startsWith("set ") ) {
      RcvCmd.replace("set " , "");
      splitParam( RcvCmd, &motor0, &motor1);
    } else if ( RcvCmd.startsWith("move ") ) {
      RcvCmd.replace("move ", "");
      splitParam( RcvCmd, &speed, &delay_ms);
      move( speed, delay_ms );
    } else if ( RcvCmd.startsWith("turn ") ) { //右前 or 左前
      RcvCmd.replace("turn ", "");
      splitParam( RcvCmd, &speed, &delay_ms);
      turn( speed, delay_ms );
    } else if ( RcvCmd.startsWith("turn0 ") ) { // 右前 or 右後
      RcvCmd.replace("turn0 ", "");
      splitParam( RcvCmd, &speed, &delay_ms);
      turn0( speed, delay_ms );
    } else if ( RcvCmd.startsWith("turn1 ") ) { // 左前 or 左後
      RcvCmd.replace("turn1 ", "");
      splitParam( RcvCmd, &speed, &delay_ms);
      turn1( speed, delay_ms );
    } else if ( RcvCmd.startsWith("rotate ") ) {
      RcvCmd.replace("rotate ", "");
      splitParam( RcvCmd, &speed, &delay_ms);
      rotate( speed, delay_ms );
    } else {
      stop();
    }
  }

  readEncder();
  //Serial.print("s0=" + String(speed_input0) + ", s1=" + String(speed_input1) + ", m0=" + String(motor0) + ", m1=" + String(motor1) + "\n");

  setMotor( motor0, motor1 );

  delay(100);
}


#define   SERIAL_BUFF_BYTE      512

String serialRead(){
  char  sSerialBuf[SERIAL_BUFF_BYTE];
  String result = "";

  if ( Serial.available() > 0 ) {
      int iPos=0;
      while (Serial.available()) {
        char c = Serial.read();
        if (  c == '\n' ) {
          break;
        } else {
          sSerialBuf[iPos] = c;
          iPos++;
          if (iPos==(SERIAL_BUFF_BYTE-1) ) {
            break;
          }
        }
      }
      sSerialBuf[iPos] = 0x00;
      result = String(sSerialBuf);
  }

  return result ;
}

int splitParam( String inStr, int *param1, int *param2 ) {
  int ret = 0;

  inStr.trim();
  int len = inStr.length();
  int pos = inStr.indexOf(' ', 0);

  if ( (pos > 0) && (len>=3) ){
    String Param1 = inStr.substring(0, pos);
    String Param2 = inStr.substring(pos, len);
    //Serial.print("Param1=" + Param1 + ", Param2=" + Param2 +"\n");
    *param1 = Param1.toInt();
    *param2 = Param2.toInt();
  } else {
    ret = -1;
  }
  return ret;
}


void setMotor(int16_t pwm0, int16_t pwm1) {
  // Value range
  int16_t m0 = constrain(pwm0, -255, 255);
  int16_t m1 = constrain(pwm1, -255, 255);

  // Dead zone
  if (((m0 > 0) && (m0 < DEAD_ZONE)) || ((m0 < 0) && (m0 > -DEAD_ZONE))) m0 = 0;
  if (((m1 > 0) && (m1 < DEAD_ZONE)) || ((m1 < 0) && (m1 > -DEAD_ZONE))) m1 = 0;

  // Same value
  static int16_t pre_m0, pre_m1;
  if ((m0 == pre_m0) && (m1 == pre_m1))
    return;
  pre_m0 = m0;
  pre_m1 = m1;

  Wire.beginTransmission(M5GO_WHEEL_ADDR);
  Wire.write(MOTOR_CTRL_ADDR); // Motor ctrl reg addr
  Wire.write(((uint8_t*)&m0)[0]);
  Wire.write(((uint8_t*)&m0)[1]);
  Wire.write(((uint8_t*)&m1)[0]);
  Wire.write(((uint8_t*)&m1)[1]);
  Wire.endTransmission();
}

void readEncder() {
  static float _speed_input0 = 0, _speed_input1 = 0;
  int16_t rx_buf[2];
  //Get Data from Module.
  Wire.beginTransmission(M5GO_WHEEL_ADDR);
  Wire.write(ENCODER_ADDR); // encoder reg addr
  Wire.endTransmission();
  Wire.beginTransmission(M5GO_WHEEL_ADDR);
  Wire.requestFrom(M5GO_WHEEL_ADDR, 4);

  if (Wire.available()) {
    ((uint8_t*)rx_buf)[0] = Wire.read();
    ((uint8_t*)rx_buf)[1] = Wire.read();
    ((uint8_t*)rx_buf)[2] = Wire.read();
    ((uint8_t*)rx_buf)[3] = Wire.read();

    // filter
    _speed_input0 *= 0.9;
    _speed_input0 += 0.1 * rx_buf[0];
    _speed_input1 *= 0.9;
    _speed_input1 += 0.1 * rx_buf[1];

    speed_input0 = constrain((int16_t)(-_speed_input0), -255, 255);
    speed_input1 = constrain((int16_t)(_speed_input1), -255, 255);
  }
}

void stop(){
  motor0 = 0;
  motor1 = 0;
}

void move(int16_t speed, uint16_t duration){
  motor0 = speed;
  motor1 = speed;
  setMotor( motor0, motor1 );

  if (duration != 0) {
    delay(duration);
    stop();
  }
}

void turn(int16_t speed, uint16_t duration){
  if (speed > 0) {
    motor0 = speed;
    motor1 = 0;
  } else if (speed < 0) {
    motor0 = 0;
    motor1 = -speed;
  }
  setMotor( motor0, motor1 );

  if (duration != 0) {
    delay(duration);
    stop();
  }
}

void turn0(int16_t speed, uint16_t duration){
  motor0 = speed;
  motor1 = 0;
  setMotor( motor0, motor1 );

  if (duration != 0) {
    delay(duration);
    stop();
  }
}

void turn1(int16_t speed, uint16_t duration){
  motor1 = 0;
  motor1 = speed;
  setMotor( motor0, motor1 );

  if (duration != 0) {
    delay(duration);
    stop();
  }
}

void rotate(int16_t speed, uint16_t duration){
  motor0 = speed;
  motor1 = -speed;
  setMotor( motor0, motor1 );

  if (duration != 0) {
    delay(duration);
    stop();
  }
}
