package com.app.ecom;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class UserService {
    private List<User> userList = new ArrayList<>();
    private Long customId = 1L;

    public List<User> fetchAllUsers(){
        return userList;
    }

    public List<User> addUser(User user){
        user.setId(customId++);
        userList.add(user);
        return userList;
    }

    public User fetchUser(Long id) {
        for(User user: userList){
            if(user.getId().equals(id)){
                return user;
            }
        }

        return null;
    }

    public boolean updateUser(Long id, User updatedUser) {
        return userList.stream()
                .filter(user -> user.getId().equals(id))
                .findFirst()
                .map(existingUser -> {
                    existingUser.setFirstName(updatedUser.getFirstName());
                    existingUser.setLastName(updatedUser.getLastName());
                    return true;
                }).orElse(false);
    }
}
