// SPDX-License-Identifier: MIT
pragma solidity ^0.8.0;

contract SimpleStorage {
    uint256 private storedValue;
    address public lastUpdater;

    event ValueChanged(address indexed updater, uint256 newValue);

    function set(uint256 _value) public {
        storedValue = _value;
        lastUpdater = msg.sender;
        emit ValueChanged(msg.sender, _value);
    }

    function get() public view returns (uint256) {
        return storedValue;
    }
}
