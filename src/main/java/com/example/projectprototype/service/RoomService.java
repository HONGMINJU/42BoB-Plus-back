package com.example.projectprototype.service;

import com.example.projectprototype.dto.ListDto;
import com.example.projectprototype.dto.RoomDto;
import com.example.projectprototype.dto.UserDto;
import com.example.projectprototype.entity.*;
import com.example.projectprototype.entity.enums.Location;
import com.example.projectprototype.entity.enums.MenuName;
import com.example.projectprototype.entity.enums.RoomStatus;
import com.example.projectprototype.mapper.RoomMapper;
import com.example.projectprototype.mapper.UserMapper;
import com.example.projectprototype.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.util.EnumUtils;

import javax.transaction.Transactional;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Long return 값
 * 양수 생성된 방의 id
 * -1 : parse error (ex : 입력된 시간의 형태가 형식에 맞지 않음)
 * -2 : 입력된 시간 + 1시간 이내에 유저가 방에 참여하고 있음.
 * -3 : userId 가 DB에 등록되지 않은 id 임.
 * -4 : enum 형 string 들 (ex : menus, location) 이 형식에 맞지 않음.
 * -5 : roomId 가 DB에 등록되지 않은 id 임.
 * -6 : room 과 user 간에 관련성이 없음.
 */

@Service
@Transactional
@RequiredArgsConstructor
public class RoomService {
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final RoomMenuRepository roomMenuRepository;
    private final MenuRepository menuRepository;
    private final ParticipantRepository participantRepository;

    private final UserService userService;
    private final ParticipantService partService;
    private final RoomMapper roomMapper;

    // 방생성 : 채팅방 생성은 설정 안되어 있음.
    // 입력된 시간이 지금보다 전이면 fail 줘야함.
    public long createRoom(RoomDto roomDTO, String userId) {
        LocalDateTime inputTime = LocalDateTime.parse(roomDTO.getMeetTime(), formatter);

        if (!isParsableTime(roomDTO.getMeetTime())) return -1L;
        if (!userService.userIdCheck(userId)) return -3L;
        if (!isInLocation(roomDTO.getLocation())) return -4L;
        for (String menuName : roomDTO.getMenus()) {
            if (!isInMenuName(menuName)) return -4L;
        }

        // 기존에 만들어진 방이 없으면 방 생성해서 return
        List<Participant> participants = participantRepository.findByUserId(userId);
        if (participants.size() == 0) {
            Room room = convertToRoom(roomDTO, userId);
            partService.setParticipate(room, userId);
            return room.getId();
        }

        // 기존에 만들어진 방이 있으면 기존 약속 시간과 비교하여 1시간 이내로 겹치면 -2 return;
        for (Participant part : participants) {
            Room room = roomRepository.findByIdAndStatus(part.getRoom().getId(), RoomStatus.active).get();
            LocalDateTime priorTime = room.getMeetTime();
            if (!isValidTime(priorTime, inputTime)) {
                return -2L;
            }
        }
        // 제약 조건을 모두 통과하면 roomDTO 를 Room 객체로 만들고 DB에 등록.
        Room room = convertToRoom(roomDTO, userId);
        // participate 테이블에도 mapping
        partService.setParticipate(room, userId);
        return room.getId();
    }

    /** 주어진 userId 가 참여중인 active 상태의 방을 DTO로 변환하여 반환
     * -null 참여중인 방이 없으면
     */
    // 참여 정보 볼때 roomStatus 까지 고려해서 필터링 할 수 있도록
    public ListDto<RoomDto> searchMyRooms(String userId) {
       // 참여한 방 정보 확인
        List<Participant> participants = participantRepository.findByUserId(userId);
       if (participants.size() ==0)
           return null;

       // 참여한 방 정보 기반으로 Room 엔티티 추출, Room 엔티티를 RoomDTO 로 변환
        // roomRepository.findByIdAndStatus 의 empty 검사는 필요없음. 이미 방 정보가 있다는걸 알고 조회하는것이기 때문.
        List<RoomDto> roomDtoList = new ArrayList<>();
       for (Participant part : participants) {
           Room room = roomRepository.findByIdAndStatus(part.getRoom().getId(), RoomStatus.active).get();
           roomDtoList.add(convertToRoomDTO(room));
       }

       // 변형된 RoomDTO List 를 ListDTO 형태로 변환.
        ListDto<RoomDto> DTO = new ListDto<>();
        DTO.setCode(200);
        DTO.setInterCode(1);
        DTO.setComponent(roomDtoList);

       return DTO;
    }

    /**
     * location default 일때는 LIKE 로 커버
     * menu 도 default 일때는 모든 메뉴를 list 에 넣도록 커버
     * time 이 default 일때는 쿼리를 따로 만들어서 커버
     */
    // time default 면 시간을 길게 줘버리면 됨.
    public List<RoomDto> searchRooms(String userId, String location, String menu,
                                     String startTime, String endTime, String keyword, Pageable pageable) {
        List<RoomDto> roomDtoList = new ArrayList<>();

        if (keyword.equals("default")) keyword = "%";
        else {
            keyword = "%" + keyword + "%";
        }
        if (location.equals("default")) location = "%";

        List<String> menuNameList = new ArrayList<>();
        getMenuName(menu, menuNameList);
        Page<Room> roomPage = getRoomPage(location, startTime, endTime, keyword, menuNameList, pageable);
        composeDto(userId, roomPage, roomDtoList);
        // 자기가 참여한 방은 제외해야함.
        // capacity 를 고려해야함.
        return roomDtoList;
    }

    public Long enterRoom(String userId, String roomId) {

        // roomId parse 할 수 있는지
        Long id = isParsableRoomId(roomId);
        if (id < 0L) return -1L;
        // roomId 로 DB 에서 room 이 조회되는지
        Optional<Room> room = roomRepository.findById(isParsableRoomId(roomId));
        if (room.isEmpty()) return -5L;
        // 기존에 참여하던 방과 지금 참여하려는 방이 시간이 겹치지 않는지
        List<Participant> participants = participantRepository.findByUserId(userId);
        if (participants.size() != 0) {
            for (Participant part : participants) {
                Room loopRoom = roomRepository.findByIdAndStatus(part.getRoom().getId(), RoomStatus.active).get();
                LocalDateTime priorTime = loopRoom.getMeetTime();
                if (!isValidTime(priorTime, room.get().getMeetTime())) {
                    return -2L;
                }
            }
        }
        // 활성화된 방인지 체크
        // 최대인원 체크
        partService.setParticipate(room.get(), userId);
        return id;
    }

    public Long exitRoom(String userId, String roomId) {
        // roomId parse 할 수 있는지
        Long id = isParsableRoomId(roomId);
        if (id < 0L) return -1L;
        // roomId 로 DB 에서 room 이 조회되는지
        Optional<Room> room = roomRepository.findById(isParsableRoomId(roomId));
        if (room.isEmpty()) return -5L;
        // 해당 room 에 userId 가 속해있는지
        if (!partService.isParticipant(room.get(), userId)) return -6L;
        // room participant 에서 제거

        // 방장 넘기는 로직

        // 만약 participant 가 0 이 되면 roomStatus 바꿔야함.
        return 1L;
    }

    private void composeDto(String userId, Page<Room> roomPage, List<RoomDto> roomDtoList) {
        for (Room room : roomPage.getContent()){
            if (!IsExcludedRoom(userId, room, roomDtoList)) {
                roomDtoList.add(convertToRoomDTO(room));
            }
        }
    }

    // user 가 벤되어 있는 방인지 확인
    private boolean IsExcludedRoom(String userId, Room room, List<RoomDto> roomDtoList) {
        for (Participant part : room.getParticipantList()) {
            for (Ban ban : part.getUser().getBanSrcList()) {
                if (ban.getDest().getId().equals(userId)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Page<Room> getRoomPage(String location, String startTime, String endTime, String keyword,
                                   List<String> menuNameList, Pageable pageable) {
        Page<Room> roomPage;
        if (startTime.equals("default") || endTime.equals("default")) {
            roomPage = roomRepository.SearchRoomsWithoutTime(location, keyword, menuNameList, pageable);
        } else {
          roomPage = roomRepository.SearchRoomsWithTime(location, startTime, endTime,
                  keyword, menuNameList, pageable);
        }
        return roomPage;
    }

    private void getMenuName(String menu, List<String> menuNameList) {
        if (menu.equals("default")) {
            for (String menuName : MenuName.getNames(MenuName.class)) {
                menuNameList.add(menuName);
            }
        } else {
            String[] menuArr = menu.split(",");
            for (String menuName : menuArr) {
                menuNameList.add(menuName);
            }
        }
    }

    // RoomDto 를 room 엔티티로 변환
    public Room convertToRoom(RoomDto roomDTO, String userId) {

        Room room = roomMapper.toEntity(roomDTO);

        room.setMeetTime(LocalDateTime.parse(roomDTO.getMeetTime(), formatter));
        room.setStatus(RoomStatus.valueOf(roomDTO.getStatus()));
        room.setOwner(userRepository.findById(userId).get());
        roomRepository.save(room);
        // Room 과 Menu 를 매핑하는 RoomMenu 객체를 만들어서 roomMenuList 에 저장
        for (String menuName : roomDTO.getMenus()) {
            RoomMenu roomMenu = new RoomMenu();
            roomMenu.setRoom(room);
            roomMenu.setMenu(menuRepository.findByName(MenuName.valueOf(menuName)));
            roomMenuRepository.save(roomMenu);
        }
        return room;
    }

    // Room 객체를 RoomDTO 로 변환
    public RoomDto convertToRoomDTO(Room room) {
        RoomDto roomDTO = roomMapper.toDto(room);

        roomDTO.setOwner(userService.convertToUserDto(room.getOwner()));

        // menu 이름 string 으로 변환
        for(RoomMenu roomMenu : room.getRoomMenuList()) {
            roomDTO.getMenus().add(roomMenu.getMenu().getName().toString());
        }

        // 방 참여자 List<User> 로 변환
        for (Participant part : room.getParticipantList()) {
            roomDTO.getParticipants().add(userService.convertToUserDto(part.getUser()));
        }
        return roomDTO;
    }

    public int paramsCheck(String location, String menu, String startTime, String endTime) {
        if (!isInLocation(location)) return -4;
        if (!isInMenuName(menu)) return -4;
        if (!isParsableTime(startTime)) return -1;
        if (!isParsableTime(endTime)) return -1;

        return 0;
    }

    /**
     * 입력된 시간 값이 기존 약속 시간에서 1시간 내외 안에 있는지 확인
     * 11:00, 12:00 : true
     * 11:00, 11:30 or 10:30 : false
     */
    public boolean isValidTime(LocalDateTime time1, LocalDateTime time2) {
        if (time1.isAfter(time2)) {
            return time2.plusHours(1).isBefore(time1);
        } else {
            return time1.plusHours(1).isBefore(time2);
        }
    }

    public boolean isParsableTime(String time) {
        if (time.equals("default")) return true;
        try {
            LocalDateTime.parse(time, formatter);
        } catch (DateTimeParseException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public Long isParsableRoomId(String roomId) {
        try {
            return Long.parseLong(roomId);
        } catch (RuntimeException e) {
            e.printStackTrace();
            return -1L;
        }
    }

    // 주어진 String 이 Location enum 에 포함된 값인지 확인
    public boolean isInLocation(String location) {
        if (location.equals("default")) return true;
        try {
            EnumUtils.findEnumInsensitiveCase(Location.class, location);
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    // 주어진 String 이 MenuName enum 에 포함된 값인지 확인
    public boolean isInMenuName(String menuName) {
        if (menuName.equals("default")) return true;
        try {
            String[] menuNameArr = menuName.split(",");
            for (String menuNameSplit : menuNameArr) {
                EnumUtils.findEnumInsensitiveCase(MenuName.class, menuNameSplit);
            }
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }
}
