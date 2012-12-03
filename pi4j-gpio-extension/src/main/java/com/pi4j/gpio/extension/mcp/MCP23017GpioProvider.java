package com.pi4j.gpio.extension.mcp;

import java.io.IOException;

import com.pi4j.io.gpio.GpioProvider;
import com.pi4j.io.gpio.GpioProviderBase;
import com.pi4j.io.gpio.Pin;
import com.pi4j.io.gpio.PinMode;
import com.pi4j.io.gpio.PinPullResistance;
import com.pi4j.io.gpio.PinState;
import com.pi4j.io.gpio.event.PinDigitalStateChangeEvent;
import com.pi4j.io.gpio.event.PinListener;
import com.pi4j.io.gpio.exception.InvalidPinException;
import com.pi4j.io.gpio.exception.InvalidPinModeException;
import com.pi4j.io.gpio.exception.UnsupportedPinModeException;
import com.pi4j.io.gpio.exception.UnsupportedPinPullResistanceException;
import com.pi4j.io.i2c.I2CBus;
import com.pi4j.io.i2c.I2CDevice;
import com.pi4j.io.i2c.I2CFactory;

/*
 * #%L
 * **********************************************************************
 * ORGANIZATION  :  Pi4J
 * PROJECT       :  Pi4J :: GPIO Extension
 * FILENAME      :  MCP23017GpioProvider.java  
 * 
 * This file is part of the Pi4J project. More information about 
 * this project can be found here:  http://www.pi4j.com/
 * **********************************************************************
 * %%
 * Copyright (C) 2012 Pi4J
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You may obtain a copy of the License
 * at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 * #L%
 */

/**
 * <p>
 * This GPIO provider implements the MCP23017 I2C GPIO expansion board as native Pi4J GPIO pins.
 * More information about the board can be found here: *
 * http://ww1.microchip.com/downloads/en/DeviceDoc/21952b.pdf
 * http://learn.adafruit.com/mcp230xx-gpio-expander-on-the-raspberry-pi/overview
 * </p>
 * 
 * <p>
 * The MCP23017 is connected via I2C connection to the Raspberry Pi and provides 16 GPIO pins that
 * can be used for either digital input or digital output pins.
 * </p>
 * 
 * @author Robert Savage
 * 
 */
public class MCP23017GpioProvider extends GpioProviderBase implements GpioProvider
{
    public static final String NAME = "com.pi4j.gpio.extension.mcp.MCP23017GpioProvider";
    public static final String DESCRIPTION = "MCP23017 GPIO Provider";
    public static final int DEFAULT_ADDRESS = 0x20;

    private static final int REGISTER_IODIR_A = 0x00;
    private static final int REGISTER_IODIR_B = 0x01;
    private static final int REGISTER_GPINTEN_A = 0x04;
    private static final int REGISTER_GPINTEN_B = 0x05;
    private static final int REGISTER_DEFVAL_A = 0x06;
    private static final int REGISTER_DEFVAL_B = 0x07;
    private static final int REGISTER_INTCON_A = 0x08;
    private static final int REGISTER_INTCON_B = 0x09;
    private static final int REGISTER_GPPU_A = 0x0C;
    private static final int REGISTER_GPPU_B = 0x0D;
    private static final int REGISTER_INTF_A = 0x0E;
    private static final int REGISTER_INTF_B = 0x0F;
    // private static final int REGISTER_INTCAP_A = 0x10;
    // private static final int REGISTER_INTCAP_B = 0x11;
    private static final int REGISTER_GPIO_A = 0x12;
    private static final int REGISTER_GPIO_B = 0x13;

    private static final int GPIO_A_OFFSET = 0;
    private static final int GPIO_B_OFFSET = 1000;

    private int currentStatesA = 0;
    private int currentStatesB = 0;
    private int currentDirectionA = 0;
    private int currentDirectionB = 0;
    private int currentPullupA = 0;
    private int currentPullupB = 0;

    private I2CBus bus;
    private I2CDevice device;
    private GpioStateMonitor monitor = null;

    public MCP23017GpioProvider(int busNumber, int address) throws IOException
    {
        // create I2C communications bus instance
        bus = I2CFactory.getInstance(busNumber);

        // create I2C device instance
        device = bus.getDevice(address);

        // set all default pins directions
        device.write(REGISTER_IODIR_A, (byte) currentDirectionA);
        device.write(REGISTER_IODIR_B, (byte) currentDirectionB);

        // set all default pin interrupts
        device.write(REGISTER_GPINTEN_A, (byte) currentDirectionA);
        device.write(REGISTER_GPINTEN_B, (byte) currentDirectionB);

        // set all default pin interrupt default values
        device.write(REGISTER_DEFVAL_A, (byte) 0x00);
        device.write(REGISTER_DEFVAL_B, (byte) 0x00);

        // set all default pin interrupt comparison behaviors
        device.write(REGISTER_INTCON_A, (byte) 0x00);
        device.write(REGISTER_INTCON_B, (byte) 0x00);

        // set all default pin states
        device.write(REGISTER_GPIO_A, (byte) currentStatesA);
        device.write(REGISTER_GPIO_B, (byte) currentStatesB);

        // set all default pin pull up resistors
        device.write(REGISTER_GPPU_A, (byte) currentPullupA);
        device.write(REGISTER_GPPU_B, (byte) currentPullupB);
    }

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public void export(Pin pin, PinMode mode)
    {
        // make sure to set the pin mode
        super.export(pin, mode);
        setMode(pin, mode);
    }

    @Override
    public void unexport(Pin pin)
    {
        super.unexport(pin);
        setMode(pin, PinMode.DIGITAL_OUTPUT);
    }

    @Override
    public void setMode(Pin pin, PinMode mode)
    {
        // validate
        if (!pin.getSupportedPinModes().contains(mode))
            throw new InvalidPinModeException(pin, "Invalid pin mode [" + mode.getName()
                    + "]; pin [" + pin.getName() + "] does not support this mode.");

        // validate
        if (!pin.getSupportedPinModes().contains(mode))
            throw new UnsupportedPinModeException(pin, mode);

        // determine A or B port based on pin address
        try
        {
            if (pin.getAddress() < GPIO_B_OFFSET)
                setModeA(pin, mode);
            else
                setModeB(pin, mode);
        }
        catch (IOException ex)
        {
            throw new RuntimeException(ex);
        }

        // cache mode
        getPinCache(pin).setMode(mode);

        // if any pins are configured as input pins, then we need to start the interrupt monitoring
        // thread
        if (currentDirectionA > 0 || currentDirectionB > 0)
        {
            // if the monitor has not been started, then start it now
            if (monitor == null)
            {
                // start monitoring thread
                monitor = new GpioStateMonitor(device);
                monitor.start();
            }
        }
        else
        {
            // shutdown and destroy monitoring thread since there are no input pins configured
            if (monitor != null)
            {
                monitor.shutdown();
                monitor = null;
            }
        }
    }

    private void setModeA(Pin pin, PinMode mode) throws IOException
    {
        // determine register and pin address
        int pinAddress = pin.getAddress() - GPIO_A_OFFSET;

        // determine update direction value based on mode
        if (mode == PinMode.DIGITAL_INPUT)
        {
            currentDirectionA |= pinAddress;
        }
        else if (mode == PinMode.DIGITAL_OUTPUT)
        {
            currentDirectionA &= ~pinAddress;
        }

        // next update direction value
        device.write(REGISTER_IODIR_A, (byte) currentDirectionA);

        // enable interrupts; interrupt on any change from previous state
        device.write(REGISTER_GPINTEN_A, (byte) currentDirectionA);
    }

    private void setModeB(Pin pin, PinMode mode) throws IOException
    {
        // determine register and pin address
        int pinAddress = pin.getAddress() - GPIO_B_OFFSET;

        // determine update direction value based on mode
        if (mode == PinMode.DIGITAL_INPUT)
        {
            currentDirectionB |= pinAddress;
        }
        else if (mode == PinMode.DIGITAL_OUTPUT)
        {
            currentDirectionB &= ~pinAddress;
        }

        // next update direction (mode) value
        device.write(REGISTER_IODIR_B, (byte) currentDirectionB);

        // enable interrupts; interrupt on any change from previous state
        device.write(REGISTER_GPINTEN_B, (byte) currentDirectionB);
    }

    @Override
    public PinMode getMode(Pin pin)
    {
        return super.getMode(pin);
    }

    @Override
    public void setState(Pin pin, PinState state)
    {
        // validate
        if (hasPin(pin) == false)
            throw new InvalidPinException(pin);

        // only permit invocation on pins set to DIGITAL_OUTPUT modes
        if (getPinCache(pin).getMode() != PinMode.DIGITAL_OUTPUT)
            throw new InvalidPinModeException(pin, "Invalid pin mode on pin [" + pin.getName()
                    + "]; cannot setState() when pin mode is ["
                    + getPinCache(pin).getMode().getName() + "]");

        try
        {
            // determine A or B port based on pin address
            if (pin.getAddress() < GPIO_B_OFFSET)
                setStateA(pin, state);
            else
                setStateB(pin, state);
        }
        catch (IOException ex)
        {
            throw new RuntimeException(ex);
        }

        // cache pin state
        getPinCache(pin).setState(state);
    }

    private void setStateA(Pin pin, PinState state) throws IOException
    {
        // determine pin address
        int pinAddress = pin.getAddress() - GPIO_A_OFFSET;

        // determine state value for pin bit
        if (state.isHigh())
        {
            currentStatesA |= pinAddress;
        }
        else
        {
            currentStatesA &= ~pinAddress;
        }

        // update state value
        device.write(REGISTER_GPIO_A, (byte) currentStatesA);
    }

    private void setStateB(Pin pin, PinState state) throws IOException
    {
        // determine pin address
        int pinAddress = pin.getAddress() - GPIO_B_OFFSET;

        // determine state value for pin bit
        if (state.isHigh())
        {
            currentStatesB |= pinAddress;
        }
        else
        {
            currentStatesB &= ~pinAddress;
        }

        // update state value
        device.write(REGISTER_GPIO_B, (byte) currentStatesB);
    }

    @Override
    public PinState getState(Pin pin)
    {
        return super.getState(pin);
    }

    @Override
    public void setPullResistance(Pin pin, PinPullResistance resistance)
    {
        // validate
        if (hasPin(pin) == false)
            throw new InvalidPinException(pin);

        // validate
        if (!pin.getSupportedPinPullResistance().contains(resistance))
            throw new UnsupportedPinPullResistanceException(pin, resistance);

        try
        {
            // determine A or B port based on pin address
            if (pin.getAddress() < GPIO_B_OFFSET)
                setPullResistanceA(pin, resistance);
            else
                setPullResistanceB(pin, resistance);
        }
        catch (IOException ex)
        {
            throw new RuntimeException(ex);
        }

        // cache resistance
        getPinCache(pin).setResistance(resistance);
    }

    private void setPullResistanceA(Pin pin, PinPullResistance resistance) throws IOException
    {
        // determine pin address
        int pinAddress = pin.getAddress() - GPIO_A_OFFSET;

        // determine pull up value for pin bit
        if (resistance == PinPullResistance.PULL_UP)
        {
            currentPullupA |= pinAddress;
        }
        else
        {
            currentPullupA &= ~pinAddress;
        }

        // next update pull up resistor value
        device.write(REGISTER_GPPU_A, (byte) currentPullupA);
    }

    private void setPullResistanceB(Pin pin, PinPullResistance resistance) throws IOException
    {
        // determine pin address
        int pinAddress = pin.getAddress() - GPIO_B_OFFSET;

        // determine pull up value for pin bit
        if (resistance == PinPullResistance.PULL_UP)
        {
            currentPullupB |= pinAddress;
        }
        else
        {
            currentPullupB &= ~pinAddress;
        }

        // next update pull up resistor value
        device.write(REGISTER_GPPU_B, (byte) currentPullupB);
    }

    @Override
    public PinPullResistance getPullResistance(Pin pin)
    {
        return super.getPullResistance(pin);
    }
    
    
    @Override
    public void shutdown()
    {
        try
        {
            // if a monitor is running, then shut it down now
            if (monitor != null)
            {
                // shutdown monitoring thread
                monitor.shutdown();
                monitor = null;
            }

            // close the I2C bus communication
            bus.close();
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }   

    
    /**
     * This class/thread is used to to actively monitor for GPIO interrupts
     * 
     * @author Robert Savage
     * 
     */
    private class GpioStateMonitor extends Thread
    {
        private I2CDevice device;
        private boolean shuttingDown = false;

        public GpioStateMonitor(I2CDevice device)
        {
            this.device = device;
        }

        public void shutdown()
        {
            shuttingDown = true;
        }

        public void run()
        {
            while (!shuttingDown)
            {
                try
                {
                    // only process for interrupts if a pin on port A is configured as an input pin
                    if (currentDirectionA > 0)
                    {
                        // process interrupts for port A
                        int pinInterruptA = device.read(REGISTER_INTF_A);

                        // validate that there is at least one interrupt active on port A
                        if (pinInterruptA > 0)
                        {
                            // read the current pin states on port A
                            int pinInterruptState = device.read(REGISTER_GPIO_A);

                            // loop over the available pins on port B
                            for (Pin pin : MCP23017Pin.ALL_A_PINS)
                            {
                                // is there an interrupt flag on this pin?
                                if ((pinInterruptA & pin.getAddress()) > 0)
                                {
                                    // System.out.println("INTERRUPT ON PIN [" + pin.getName() + "]");
                                    evaluatePinForChangeA(pin, pinInterruptState);
                                }
                            }
                        }
                    }

                    // only process for interrupts if a pin on port B is configured as an input pin
                    if (currentDirectionB > 0)
                    {
                        // process interrupts for port B
                        int pinInterruptB = device.read(REGISTER_INTF_B);

                        // validate that there is at least one interrupt active on port B
                        if (pinInterruptB > 0)
                        {
                            // read the current pin states on port B
                            int pinInterruptState = device.read(REGISTER_GPIO_B);

                            // loop over the available pins on port B
                            for (Pin pin : MCP23017Pin.ALL_B_PINS)
                            {
                                // is there an interrupt flag on this pin?
                                if ((pinInterruptB & pin.getAddress()) > 0)
                                {
                                    // System.out.println("INTERRUPT ON PIN [" + pin.getName() + "]");
                                    evaluatePinForChangeB(pin, pinInterruptState);
                                }
                            }
                        }
                    }

                    // ... lets take a short breather ...
                    Thread.currentThread();
                    Thread.sleep(50);
                }
                catch (Exception ex)
                {
                    ex.printStackTrace();
                }
            }
        }

        private void evaluatePinForChangeA(Pin pin, int state)
        {
            if (getPinCache(pin).isExported())
            {
                // determine pin address
                int pinAddress = pin.getAddress() - GPIO_A_OFFSET;

                if ((state & pinAddress) != (currentStatesA & pinAddress))
                {
                    PinState newState = (state & pinAddress) == pinAddress ? PinState.HIGH
                            : PinState.LOW;

                    // cache state
                    getPinCache(pin).setState(newState);

                    // determine and cache state value for pin bit
                    if (newState.isHigh())
                    {
                        currentStatesA |= pinAddress;
                    }
                    else
                    {
                        currentStatesA &= ~pinAddress;
                    }

                    // change detected for INPUT PIN
                    // System.out.println("<<< CHANGE >>> " + pin.getName() + " : " + state);
                    dispatchPinChangeEvent(pin.getAddress(), newState);
                }
            }
        }

        private void evaluatePinForChangeB(Pin pin, int state)
        {
            if (getPinCache(pin).isExported())
            {
                // determine pin address
                int pinAddress = pin.getAddress() - GPIO_B_OFFSET;

                if ((state & pinAddress) != (currentStatesB & pinAddress))
                {
                    PinState newState = (state & pinAddress) == pinAddress ? PinState.HIGH
                            : PinState.LOW;

                    // cache state
                    getPinCache(pin).setState(newState);

                    // determine and cache state value for pin bit
                    if (newState.isHigh())
                    {
                        currentStatesB |= pinAddress;
                    }
                    else
                    {
                        currentStatesB &= ~pinAddress;
                    }

                    // change detected for INPUT PIN
                    // System.out.println("<<< CHANGE >>> " + pin.getName() + " : " + state);
                    dispatchPinChangeEvent(pin.getAddress(), newState);
                }
            }
        }

        private void dispatchPinChangeEvent(int pinAddress, PinState state)
        {
            // iterate over the pin listeners map
            for (Pin pin : listeners.keySet())
            {
                // System.out.println("<<< DISPATCH >>> " + pin.getName() + " : " +
                // state.getName());

                // dispatch this event to the listener
                // if a matching pin address is found
                if (pin.getAddress() == pinAddress)
                {
                    // dispatch this event to all listener handlers
                    for (PinListener listener : listeners.get(pin))
                    {
                        listener.handlePinEvent(new PinDigitalStateChangeEvent(this, pin, state));
                    }
                }
            }
        }
    }
}
